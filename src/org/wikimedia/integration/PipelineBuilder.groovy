package org.wikimedia.integration

import org.wikimedia.integration.ExecutionGraph
import org.wikimedia.integration.Pipeline

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
   */
  void build(ws) {
    def config

    ws.node {
      ws.stage("configure") {
        ws.checkout(ws.scm)
        config = ws.readYaml(file: configPath)
      }
    }

    for (def pline in pipelines(config)) {
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
              ws.stage("${pline.name}: ${stage.name}", stage.closure(ws))
            }
          }
        } catch (exception) {
          ws.currentBuild.result = 'FAILURE'

          // ensure teardown steps are always executed
          for (def stage in stack.last()) {
            if (stage == "_teardown") {
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
