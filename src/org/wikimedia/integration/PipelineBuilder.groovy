package org.wikimedia.integration

import static org.wikimedia.integration.Utility.arg
import static org.wikimedia.integration.Utility.args
import static org.wikimedia.integration.Utility.collectAllNested
import static org.wikimedia.integration.Utility.randomAlphanum

import org.wikimedia.integration.ExecutionGraph
import org.wikimedia.integration.PatchSet
import org.wikimedia.integration.Pipeline
import org.wikimedia.integration.PipelineRunner
import org.wikimedia.integration.PipelineStage

class PipelineBuilder implements Serializable {
  /**
   * Path to the project's pipeline configuration file.
   */
  String configPath

  /**
   * Additional {@link PipelineRunner} properties to set.
   */
  Map runnerOverrides

  /**
   * Constructs a new {@PipelineBuilder} from the given YAML configuration.
   *
   * @param overrides Named parameters used to override properties of the
   *                  {@link PipelineRunner} during execution. See
   *                  {@link runnerOverrides}.
   * @param pipelineConfigPath Path to the pipeline configuration relative to
   *                           the project's root directory.
   *
   */
  PipelineBuilder(Map overrides = [:], String pipelineConfigPath) {
    configPath = pipelineConfigPath
    runnerOverrides = overrides
  }

  /**
   * Builds a single-node Jenkins workflow script for each of the configured
   * pipelines.
   *
   * If a pipeline defines any branching arcs in its directed
   * <code>execution</code> graph, they will be iterated over concurrently—in
   * the order that {@link ExecutionGraph#executions()} returns—and their
   * stages defined as <code>parallel</code> stages in the workflow script.
   *
   * @param ws Jenkins Workflow Script (`this` when writing a Jenkinsfile)
   * @param pipelineName Only build/run the given pipeline.
   */
  void build(ws, pipelineName = "") {
    def config

    ws.node(Pipeline.baseNodeLabel) {
      ws.stage("configure") {
        if (ws.params.ZUUL_REF) {
          ws.checkout(PatchSet.fromZuul(ws.params).getSCM(
            submodules: false,
            depth: 1,
            tags: false,
          ))
        } else {
          ws.checkout(collectAllNested(ws.scm, {
            if (it instanceof Map) {
              if (it['$class'] == 'CloneOption') {
                it.depth = 1
                it.shallow = true
                it.noTags = true
              }

              if (it['$class'] == 'SubmoduleOption') {
                it.disableSubmodules = true
              }
            }

            return it
          }))
        }

        def validator = new Validator(ws: ws)
        validator.assertValid(configPath)

        config = ws.readYaml(file: configPath)
      }
    }

    def plines = pipelines(config)

    if (pipelineName) {
      plines = plines.findAll { it.name == pipelineName }

      if (plines.size() == 0) {
        throw new RuntimeException(
          "Pipeline '${pipelineName}' is not defined in project's '${configPath}'",
        )
      }
    }

    for (def pline in plines) {
      def stack = pline.stack()

      ws.node(pline.getRequiredNodeLabels().join(" && ")) {
        try {
          for (def stages in stack) {
            if (stages.size() > 1) {
              def stageClosures = [:]
              for (def stage in stages) {
                stageClosures[stage.name] = stage.closure(ws)
              }

              ws.stage("${pline.name}: [parallel]") {
                ws.parallel(stageClosures)
              }
            } else {
              def stage = stages[0]

              // if we've reached the teardown stage in our normal execution
              // path (not after an exception was thrown), the result should
              // be a success
              if (stage.name == PipelineStage.TEARDOWN) {
                ws.currentBuild.result = 'SUCCESS'
              }

              ws.stage("${pline.name}: ${stage.name}", stage.closure(ws))
            }
          }
        } catch (exception) {
          ws.currentBuild.result = 'FAILURE'

          // ensure teardown steps are always executed
          for (def stage in stack.last()) {
            if (stage.name == PipelineStage.TEARDOWN) {
              ws.echo "exception caught. executing teardown tasks..."
              stage.closure(ws)()
            }
          }

          throw exception
        }
      }
    }
  }

  /**
   * Constructs and returns all pipelines from the given configuration.
   */
  List pipelines(cfg) {
    cfg.pipelines.collect { pname, pconfig ->
      def pline = new Pipeline(pname, pconfig, runnerOverrides)
      pline.validate()
      pline
    }
  }

  /**
   * Validates user provided configuration against the defined schema using a
   * tool called ajv (Another JSON Validator).
   */
  class Validator implements Serializable {
    def ws

    private final String AJV = 'docker-registry.wikimedia.org/releng/ajv:latest'
    private final String SCHEMA = 'org/wikimedia/integration/schema/pipelines.v0.yaml'

    /**
     * Validates the given configuration against the schema.
     *
     * @param configPath Path to configuration file.
     */
    void assertValid(String configPath) {
      def runner = new PipelineRunner(runnerOverrides, ws)

      runner.withTempDirectory() { tempDir ->
        ws.timeout(time: 10, unit: "MINUTES") {
          def containerName = "plib-validate-${randomAlphanum(8)}"

          ws.sh(sprintf(
            'docker container create --rm --attach STDOUT --attach STDERR --name %s %s',
            arg(containerName),
            args([AJV, '--spec=draft2020', '--errors=text', '-s', 'schema.yaml', '-d', 'config.yaml'])
          ))

          try {
            def schemaFile = [tempDir, 'schema.yaml'].join('/')
            ws.writeFile(file: schemaFile, text: ws.libraryResource(SCHEMA))

            ws.sh(sprintf('docker cp %s %s:/workspace/schema.yaml', arg(schemaFile), arg(containerName)))
            ws.sh(sprintf('docker cp %s %s:/workspace/config.yaml', arg(configPath), arg(containerName)))
          } catch (Exception e) {
            ws.sh(sprintf('docker rm %s', arg(containerName)))
            throw e
          }

          ws.sh(sprintf('docker container start --attach %s', arg(containerName)))
        }
      }
    }
  }
}
