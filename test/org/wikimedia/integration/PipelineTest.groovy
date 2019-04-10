import groovy.mock.interceptor.MockFor
import static groovy.test.GroovyAssert.*
import groovy.util.GroovyTestCase

import org.wikimedia.integration.Pipeline

class PipelineTest extends GroovyTestCase {
  void testConstructor() {
    def pipeline = new Pipeline("foo", [
      blubberfile: "bar/blubber.yaml",
      directory: "src/foo",
      stages: [
        [name: "unit"],
        [name: "lint"],
        [name: "candidate"],
        [name: "production"],
      ],
      execution: [
        ["unit", "candidate", "production"],
        ["lint", "candidate", "production"],
      ],
    ])

    assert pipeline.blubberfile == "bar/blubber.yaml"
    assert pipeline.directory == "src/foo"
    assert pipeline.execution == [
      ["unit", "candidate", "production"],
      ["lint", "candidate", "production"],
    ]
  }

  void testConstructor_defaults() {
    def pipeline = new Pipeline("foo", [
      directory: "src/foo",
      stages: [
        [name: "unit"],
        [name: "lint"],
        [name: "candidate"],
        [name: "production"],
      ],
    ])

    assert pipeline.blubberfile == "foo/blubber.yaml"

    assert pipeline.execution == [
      ["unit", "lint", "candidate", "production"],
    ]
  }

  void testRunner() {
    def pipeline = new Pipeline("foo", [
      directory: "src/foo/",
      stages: [],
    ])

    assert pipeline.runner().configPath == "../../.pipeline"
  }

  void testRunner_currentDirectory() {
    def pipeline = new Pipeline("foo", [
      directory: ".",
      stages: [],
    ])

    assert pipeline.runner().configPath == ".pipeline"
  }

  void testValidate_setupReserved() {
    def pipeline = new Pipeline("foo", [
      stages: [[name: "setup"]],
    ])

    def e = shouldFail(Pipeline.ValidationException) {
      pipeline.validate()
    }

    assert e.errors.size() == 1
    assert e.errors[0] == "setup is a reserved stage name"
  }

  void testValidate_teardownReserved() {
    def pipeline = new Pipeline("foo", [
      stages: [[name: "teardown"]],
    ])

    def e = shouldFail(Pipeline.ValidationException) {
      pipeline.validate()
    }

    assert e.errors.size() == 1
    assert e.errors[0] == "teardown is a reserved stage name"
  }
}
