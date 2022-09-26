import groovy.mock.interceptor.MockFor
import static groovy.test.GroovyAssert.*
import groovy.util.GroovyTestCase

import org.wikimedia.integration.Pipeline

class PipelineTest extends GroovyTestCase {
  void testConstructor() {
    def pipeline = new Pipeline("foo", [
      blubberfile: "bar/blubber.yaml",
      directory: "src/foo",
      fetch: [
        shallow: true,
        depth: 50,
        submodules: false,
      ],
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
    assert pipeline.fetchOptions.shallow == true
    assert pipeline.fetchOptions.depth == 50
    assert pipeline.fetchOptions.submodules == false
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
    assert pipeline.fetchOptions == [:]
    assert pipeline.execution == [
      ["unit", "lint", "candidate", "production"],
    ]
  }

  void testGetDefaultNodeLabels_default() {
    def pipeline = new Pipeline("foo", [
      stages: [],
    ])

    assert pipeline.getRequiredNodeLabels() == ["pipelinelib"] as Set
  }

  void testGetDefaultNodeLabels_build() {
    def pipeline = new Pipeline("foo", [
      stages: [
        [
          name: "foo",
          build: "foo",
        ],
      ],
    ])

    assert pipeline.getRequiredNodeLabels() == ["pipelinelib", "blubber"] as Set
  }

  void testGetDefaultNodeLabels_run() {
    def pipeline = new Pipeline("foo", [
      stages: [
        [
          name: "foo",
          run: [
            image: "foo",
          ],
        ],
      ],
    ])

    assert pipeline.getRequiredNodeLabels() == ["pipelinelib", "blubber"] as Set
  }

  void testGetDefaultNodeLabels_copy() {
    def pipeline = new Pipeline("foo", [
      stages: [
        [
          name: "foo",
          copy: ["foo/*"],
        ],
      ],
    ])

    assert pipeline.getRequiredNodeLabels() == ["pipelinelib", "blubber"] as Set
  }

  void testGetDefaultNodeLabels_publishImage() {
    def pipeline = new Pipeline("foo", [
      stages: [
        [
          name: "foo",
          publish: [
            image: [
              id: "foo",
            ],
          ],
        ],
      ],
    ])

    assert pipeline.getRequiredNodeLabels() == ["pipelinelib", "dockerPublish"] as Set
  }

  void testGetDefaultNodeLabels_promoteImage() {
    def pipeline = new Pipeline("foo", [
      stages: [
        [
          name: "foo",
          promote: true
        ],
      ],
    ])

    assert pipeline.getRequiredNodeLabels() == ["pipelinelib", "chartPromote"] as Set
  }

  void testRunner() {
    def pipeline = new Pipeline("foo", [
      directory: "src/foo/",
      stages: [],
    ])

    def runner = pipeline.runner()

    assert runner.configPath == "../../.pipeline"
    assert runner.registry == "docker-registry.wikimedia.org"
    assert runner.registryInternal == "docker-registry.discovery.wmnet"
  }

  void testRunner_currentDirectory() {
    def pipeline = new Pipeline("foo", [
      directory: ".",
      stages: [],
    ])

    assert pipeline.runner().configPath == ".pipeline"
  }

  void testRunner_withOverrides() {
    def pipeline = new Pipeline(
      "foo",
      [
        directory: ".",
        stages: []
      ],
      [
        registry: "foo.example",
      ]
    )

    assert pipeline.runner().registry == "foo.example"
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

  void testValidate_executionListOfBranches() {
    def pipeline = new Pipeline("foo", [
      stages: [[name: "foo"], [name: "bar"]],
      execution: ["foo", "bar"],
    ])

    def e = shouldFail(Pipeline.ValidationException) {
      pipeline.validate()
    }

    assert e.errors.size() == 1
    assert e.errors[0] == "`execution` graph must be a list of lists (graph branches)"
  }

  void testValidate_containsProhibitedActions() {
    def pipeline = new Pipeline(
      "foo",
      [
        stages: [[name: "foo", publish: [ image: true ]]],
      ],
      [:],
      [ "build", "run" ], // allow only certain actions
    )

    def e = shouldFail(Pipeline.ValidationException) {
      pipeline.validate()
    }

    assert e.errors.size() == 1
    assert e.errors[0] == "stage `foo` contains an action (`publish`) that is not allowed by this pipeline"
  }

  void testStack() {
    def pipeline = new Pipeline("foo", [
      stages: [[name: "stageA"], [name: "stageB"], [name: "stageC"]],
      execution: [["stageA", "stageC"], ["stageB", "stageC"]],
    ])

    // Expecting:
    //
    //         stageA
    //       ⇗        ⇘
    // setup            stageC  ⇒  teardown
    //       ⇘        ⇗
    //         stageB
    //
    def stack = pipeline.stack()

    assert stack.size() == 4

    assert stack[0].size() == 1
    assert stack[0][0].name == "setup"

    assert stack[1].size() == 2
    assert stack[1][0].name == "stageA"
    assert stack[1][1].name == "stageB"

    assert stack[2].size() == 1
    assert stack[2][0].name == "stageC"

    assert stack[3].size() == 1
    assert stack[3][0].name == "teardown"
  }
}
