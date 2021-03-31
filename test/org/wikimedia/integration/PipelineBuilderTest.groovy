import groovy.mock.interceptor.MockFor
import static groovy.test.GroovyAssert.*
import groovy.util.GroovyTestCase

import org.wikimedia.integration.PipelineBuilder

class PipelineBuilderTest extends GroovyTestCase {
  private class WorkflowScript { // Mock for Jenkins Pipeline workflow context

  }

  void testConstructor() {
    def builder = new PipelineBuilder(".pipeline/foo.yaml", registry: "alternate.example")

    assert builder.runnerOverrides == [registry: "alternate.example"]
    assert builder.configPath == ".pipeline/foo.yaml"
  }

  void testBuild() {
    def ws = new MockFor(WorkflowScript)
    def builder = new PipelineBuilder(".pipeline/foo.yaml")
    def fakeBuild = [ result: null ]

    ws.demand.with {
      // 1. Executes on any "pipelinelib" node
      node { label, closure ->
        assert label == "pipelinelib"
        closure()
      }

      // 2. Invokes a built-in stage called "configure"
      stage { stageName, closure ->
        assert stageName == "configure"
        closure()
      }

      // 2. Expects ZUUL_ parameters with which to fetch the project and patchset
      getParams(2) {
        [
          ZUUL_REF: "foo-ref",
          ZUUL_COMMIT: "foo-commit",
          ZUUL_PROJECT: "foo-project",
          ZUUL_URL: "git://server.example",
        ]
      }

      // 3. Checks out the patchset
      checkout { scm ->
        assert scm == [
          $class: "GitSCM",
          userRemoteConfigs: [ [ url: "git://server.example/foo-project", refspec: "foo-ref"] ],
          branches: [ [ name: "foo-commit" ] ],
          extensions: [
            [ $class: "CloneOption", shallow: true, depth: 1, noTags: true ],
            [ $class: "CleanBeforeCheckout", deleteUntrackedNestedRepositories: true ],
            [ $class: "SubmoduleOption", disableSubmodules: true ],
          ]
        ]
      }

      // 4. Reads in the pipeline config file
      readYaml { args ->
        assert args.file == builder.configPath

        [
          pipelines: [
            'foo-pipeline': [
              stages: [
                [ name: "stageOne" ],
                [ name: "stageTwo" ],
              ]
            ]
          ]
        ]
      }

      // 5. Executes the given pipeline on a new node.
      node { label, closure ->
        assert label == "pipelinelib && blubber"
        closure()
      }

      // 6. Executes the internal setup stage
      stage { name, _ ->
        assert name == "foo-pipeline: setup"
        // Skip calling the stage implementation in this test
      }

      // 7.a. Executes the configured stages
      stage { name, _ ->
        assert name == "foo-pipeline: stageOne"
        // Skip calling the stage implementation in this test
      }

      // 7.b. Executes the configured stages
      stage { name, _ ->
        assert name == "foo-pipeline: stageTwo"
        // Skip calling the stage implementation in this test
      }

      // 6. Executes the internal teardown stage which sets the build result
      getCurrentBuild { fakeBuild }

      stage { name, _ ->
        assert name == "foo-pipeline: teardown"
        // Skip calling the stage implementation in this test
      }
    }

    ws.use {
      builder.build(new WorkflowScript(), "foo-pipeline")

      assert fakeBuild.result == "SUCCESS"
    }
  }
}
