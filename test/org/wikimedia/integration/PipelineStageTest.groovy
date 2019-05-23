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

  void testDefaultConfig() {
    // shorthand with just name is: build and run a variant
    def shortHand = [name: "foo"]
    assert PipelineStage.defaultConfig(shortHand) == [
      name: "foo",
      build: '${.stage}',
      run: [
        image: '${.imageID}',
        arguments: [],
      ],
    ]

    // run: true means run the built image
    def runTrue = [name: "foo", build: "foo", run: true]
    assert PipelineStage.defaultConfig(runTrue) == [
      name: "foo",
      build: "foo",
      run: [
        image: '${.imageID}',
        arguments: [],
      ],
    ]

    def defaultPublishImage = PipelineStage.defaultConfig([publish: [image: true]])

    // publish.image.id defaults to the previously built image
    assert defaultPublishImage.publish.image.id == '${.imageID}'

    // publish.image.name defaults to the project name
    assert defaultPublishImage.publish.image.name == '${setup.project}'

    // publish.image.tag defaults to {timestamp}-{stage name}
    assert defaultPublishImage.publish.image.tag == '${setup.timestamp}-${.stage}'
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
