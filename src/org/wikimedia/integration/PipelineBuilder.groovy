package org.wikimedia.integration

import org.wikimedia.integration.ExecutionGraph
import org.wikimedia.integration.PatchSet
import org.wikimedia.integration.Pipeline
import org.wikimedia.integration.PipelineStage

class PipelineBuilder implements Serializable {
  String configPath

  /**
   * Constructs a new {@PipelineBuilder} from the given YAML configuration.
   */
  PipelineBuilder(String pipelineConfigPath) {
    configPath = pipelineConfigPath
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

    ws.node("blubber") {
      ws.stage("configure") {
        if (ws.params.ZUUL_REF) {
          ws.checkout(PatchSet.fromZuul(ws.params).getSCM())
        } else {
          ws.checkout(ws.scm)
        }

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
            if (stage == PipelineStage.TEARDOWN) {
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
      def pline = new Pipeline(pname, pconfig)
      pline.validate()
      pline
    }
  }
}
