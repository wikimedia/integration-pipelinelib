import groovy.mock.interceptor.MockFor
import static groovy.test.GroovyAssert.*
import groovy.util.GroovyTestCase

import org.wikimedia.integration.Pipeline
import org.wikimedia.integration.PipelineRunner
import org.wikimedia.integration.PipelineStage
import org.wikimedia.integration.ExecutionGraph
import org.wikimedia.integration.ExecutionContext

class PipelineStageTest extends GroovyTestCase {
  private class WorkflowScript {} // Mock for Jenkins Pipeline workflow context

  void testDefaultConfig_shortHand() {
    // shorthand with just name is: build and run a variant
    def cfg = [name: "foo"]

    assert PipelineStage.defaultConfig(cfg) == [
      name: "foo",
      build: '${.stage}',
      run: [
        image: '${.imageID}',
        arguments: [],
      ],
    ]
  }

  void testDefaultConfig_run() {
    def cfg = [
      name: "foo",
      build: "foo",
      run: true,
    ]

    assert PipelineStage.defaultConfig(cfg) == [
      name: "foo",
      build: "foo",

      // run: true means run the built image
      run: [
        image: '${.imageID}',
        arguments: [],
      ],
    ]
  }

  void testDefaultConfig_publish() {
    def cfg = [
      publish: [
        image: true,
      ],
    ]

    assert PipelineStage.defaultConfig(cfg) == [
      publish: [
        image: [
          // defaults to the previously built image
          id: '${.imageID}',

          // defaults to the project name
          name: '${setup.project}',

          // defaults to the pipeline start timestamp and this stage name
          tag: '${setup.timestamp}-${.stage}',

          // defaults to []
          tags: [],
        ],
      ],
    ]
  }

  void testDefaultConfig_deploy() {
    def cfg = [
      deploy: [
        chart: "chart.tar.gz",
      ],
    ]

    assert PipelineStage.defaultConfig(cfg) == [
      deploy: [
        chart: "chart.tar.gz",

        // defaults to the previously published image
        image: '${.publishedImage}',

        // defaults to "ci"
        cluster: "ci",

        // defaults to true
        test: true,
      ],
    ]

    cfg = [
      deploy: [
        chart: "chart.tar.gz",
        image: "fooimage",
        cluster: "foocluster",
        test: true,
      ],
    ]

    assert PipelineStage.defaultConfig(cfg) == [
      deploy: [
        chart: "chart.tar.gz",
        image: "fooimage",
        cluster: "foocluster",
        test: true,
      ],
    ]
  }

  void testExports() {
    def pipeline = new Pipeline("foo", [
      stages: [
        [
          name: "bar",
          exports: [
            image: "fooimage",
          ],
        ]
      ]
    ])

    def mockRunner = new MockFor(PipelineRunner)
    def mockWS = new MockFor(WorkflowScript)

    def stage = pipeline.stack()[1][0]

    assert stage.name == "bar"

    mockWS.use {
      mockRunner.use {
        stage.exports(mockWS, mockRunner)
      }
    }

    assert stage.context["image"] == "fooimage"
  }
}
