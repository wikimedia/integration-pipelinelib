package org.wikimedia.integration

import org.codehaus.groovy.GroovyException

import static org.wikimedia.integration.PipelineStage.*

import org.wikimedia.integration.ExecutionContext
import org.wikimedia.integration.ExecutionGraph
import org.wikimedia.integration.PipelineStage
import org.wikimedia.integration.PipelineRunner

/**
 * Defines a Jenkins Workflow based on a given configuration.
 *
 * The given configuration should look like this:
 *
 * <pre><code>
 * pipelines:
 *   serviceOne:
 *     blubberfile: serviceOne/blubber.yaml           # default based on service name for the dir
 *     directory: src/serviceOne
 *     fetch:
 *       shallow: true                                # Perform a shallow clone
 *       depth: 2                                     # Git fetch depth
 *       submodules: true                             # Checkout submodules
 *       tags: true                                   # Fetch all remote tags
 *     notify:
 *       email:
 *         to: [engprod@lists.wikimedia.org]          # email failures
 *     execution:                                     # directed graph of stages to run
 *       - [unit, candidate]                          # each arc is represented horizontally
 *       - [lint, candidate]
 *       - [candidate, staging, production]           # common segments of arcs can be defined separately too
 *     stages:                                        # stage defintions
 *       - name: unit                                 # stage name (required)
 *         build: phpunit                             # build an image variant
 *         run: "${.imageID}"                         # run the built image
 *         copy:                                      # copy files from a previously run container
 *           - source: "foo/*"                        # copy files foo/* from the container fs
 *             destination: "output/"                 # copy files into output/
 *       - name: lint                                 # default (build/run "lint" variant, no artifacts, etc.)
 *       - name: candidate
 *         build: production
 *         publish:
 *           image:                                   # publish built image to our docker registry
 *             id: "${.imageID}"                      # image reference
 *             name: "${setup.project}"               # image name
 *             tag: "${setup.timestamp}-${.stage}"    # primary tag
 *             tags: [candidate]                      # additional tags
 *         exports:                                   # export stage values under new names
 *           image: "${.imageFullName}:${.imageTag}"  # new variable name and interpolated value
 *       - name: staging
 *         deploy:                                    # deploy image to a cluster
 *           image: "${candidate.image}"              # image name:tag reference
 *           cluster: ci                              # default "ci" k8s cluster
 *           chart: http://helm/chart                 # helm chart to use for deployment
 *           test: true                               # run `helm test` on deployment
 *           overrides:                               # provide additional values to helm chart
 *             test:
 *               image: "tester"
 *               tag: "stable"
 *       - name: production
 *         deploy:
 *           cluster: production
 *           chart: http://helm/chart
 *   serviceTwo:
 *     directory: src/serviceTwo
 * </code></pre>
 */
class Pipeline implements Serializable {
  String name
  String blubberfile
  String directory
  Map fetchOptions
  Map notify

  private static String baseNodeLabel = "pipelinelib"
  private Map stagesConfig
  private Map runnerOverrides
  private List<List> execution

  /**
   * Constructs a new pipeline with the given name and configuration.
   */
  Pipeline(String pipelineName, Map config, Map overrides = [:]) {
    name = pipelineName
    blubberfile = config.blubberfile ?: "${name}/blubber.yaml"
    directory = config.directory ?: "."
    fetchOptions = config.fetch ?: [:]
    notify = config.notify ?: [:]
    runnerOverrides = overrides

    stagesConfig = config.stages.collectEntries {
      [(it.name): PipelineStage.defaultConfig(it)]
    }

    execution = config.execution ?: [config.stages.collect { it.name }]
  }

  /**
   * Returns a set of node labels that will be required for this pipeline to
   * function correctly.
   */
  Set getRequiredNodeLabels() {
    def labels = [baseNodeLabel] as Set

    for (def nodes in stack()) {
      for (def node in nodes) {
        labels += node.getRequiredNodeLabels()
      }
    }

    labels
  }

  /**
   * Returns the pipeline's stage stack bound with an execution context.
   */
  List stack() {
    def graph = setup() + (new ExecutionGraph(execution)) + teardown()
    def context = new ExecutionContext(graph)

    graph.stack().collect {
      it.collect { stageName ->
        createStage(stageName, context.ofNode(stageName))
      }
    }
  }

  /**
   * Returns a {@link PipelineRunner} for this pipeline and the given workflow
   * script object.
   */
  PipelineRunner runner(ws) {
    def settings = [
      kubeConfig: "/etc/kubernetes/ci-staging.config",
    ]

    def runner = new PipelineRunner(settings + runnerOverrides, ws)

    // make the PipelineRunner configPath relative to the pipeline's directory
    def prefix = "../" * directory.split('/').count { !(it in ["", "."]) }
    runner.configPath = prefix + runner.configPath

    runner
  }

  /**
   * Validates the pipeline configuration, throwing a {@link ValidationException}
   * if anything is amiss.
   */
  void validate() throws ValidationException {
    def errors = []

    // TODO expand validation
    if (PipelineStage.SETUP in stagesConfig) {
      errors += "${PipelineStage.SETUP} is a reserved stage name"
    }

    if (PipelineStage.TEARDOWN in stagesConfig) {
      errors += "${PipelineStage.TEARDOWN} is a reserved stage name"
    }

    if (!(execution instanceof List && execution.every { it instanceof List})) {
      errors += "`execution` graph must be a list of lists (graph branches)"
    }

    if (errors) {
      throw new ValidationException(errors: errors)
    }
  }

  private ExecutionGraph setup() {
    new ExecutionGraph([[PipelineStage.SETUP]])
  }

  private ExecutionGraph teardown() {
    new ExecutionGraph([[PipelineStage.TEARDOWN]])
  }

  private PipelineStage createStage(stageName, context) {
    new PipelineStage(
      this,
      stageName,
      stagesConfig[stageName] ? stagesConfig[stageName] : [:],
      context,
    )
  }

  class ValidationException extends GroovyException {
    def errors

    String getMessage() {
      def msgs = errors.collect { " - ${it}" }.join("\n")

      "Pipeline configuration validation failed:\n${msgs}"
    }
  }
}
