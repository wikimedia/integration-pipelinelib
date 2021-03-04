import groovy.mock.interceptor.MockFor
import static groovy.test.GroovyAssert.*
import groovy.util.GroovyTestCase

import java.net.URI

import org.wikimedia.integration.Pipeline
import org.wikimedia.integration.PipelineRunner
import org.wikimedia.integration.PipelineStage
import org.wikimedia.integration.ExecutionGraph
import org.wikimedia.integration.ExecutionContext

class PipelineStageTest extends GroovyTestCase {
  private class WorkflowScript { } // Mock for Jenkins Pipeline workflow context

  void testDefaultConfig_shortHand() {
    // shorthand with just name is: build and run a variant
    def cfg = [name: "foo"]

    assert PipelineStage.defaultConfig(cfg) == [
      name: "foo",
      build: [
        variant: '${.stage}',
        context: '.',
      ],
      run: [
        image: '${.imageID}',
        arguments: [],
        env: [:],
        credentials: [],
        tail: 0,
      ],
    ]
  }

  void testDefaultConfig_build() {
    def cfg = [
      name: "foo",
      build: [
        context: ".",
      ],
    ]

    assert PipelineStage.defaultConfig(cfg) == [
      name: "foo",
      build: [
        variant: '${.stage}',
        context: ".",
      ],
    ]

    cfg = [
      name: "foo",
      build: [
        variant: "bar",
      ],
    ]

    assert PipelineStage.defaultConfig(cfg) == [
      name: "foo",
      build: [
        variant: "bar",
        context: ".",
      ],
    ]
  }

  void testDefaultConfig_run() {
    def cfg = [
      name: "foo",
      build: "bar",
      run: true,
    ]

    assert PipelineStage.defaultConfig(cfg) == [
      name: "foo",
      build: [
        variant: "bar",
        context: ".",
      ],

      // run: true means run the built image
      run: [
        image: '${.imageID}',
        arguments: [],
        env: [:],
        credentials: [],
        tail: 0,
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

  void testDefaultConfig_promote() {
    def cfg = [
      promote: true,
    ]

    assert PipelineStage.defaultConfig(cfg) == [
      promote: [
        [
          chart: '${setup.projectShortName}',

          environments: [],

          version: '${.imageTag}',
        ]
      ]
    ]
  }

  void testDefaultConfig_deploy() {
    def cfg = [
      deploy: [
          chart: [:]
      ],
    ]

    assert PipelineStage.defaultConfig(cfg) == [
      deploy: [
        chart: [
          name: '${setup.projectShortName}',
          version: "",
        ],

        // defaults to the previously published image
        image: '${.publishedImage}',

        // defaults to "ci"
        cluster: "ci",

        // defaults to true
        test: true,

        // defaults to [:]
        overrides: [:],
      ],
    ]

    cfg = [
      deploy: [
        image: "fooimage",
        cluster: "foocluster",
        test: true,
        overrides: [
          foo: [ bar: "x" ],
          baz: "y",
        ],
      ],
    ]

    assert PipelineStage.defaultConfig(cfg) == [
      deploy: [
        chart: [
          name: '${setup.projectShortName}',
          version: "",
        ],
        image: "fooimage",
        cluster: "foocluster",
        test: true,
        overrides: [
          foo: [ bar: "x" ],
          baz: "y",
        ],
      ],
    ]
  }

  void testBuild() {
    def pipeline = new Pipeline("foo", [
      stages: [
        [
          name: "foo",
          blubberfile: [
            version: "v4",
            base: '${.stage}',
            variants: [
              foo: [:]
            ],
          ],
          build: [
            variant: '${.stage}variant',
            context: '${.stage}/dir',
            excludes: [".git", '${.stage}/docs/*'],
          ],
        ],
      ]
    ])

    def mockRunner = new MockFor(PipelineRunner)
    def stack = pipeline.stack()
    def stage = stack[1][0]

    stubStageContexts(stack, [
      setup: { ctx ->
        ctx["imageLabels"] = [foo: "foolabel", bar: "barlabel"]
      },
    ])

    mockRunner.demand.withBlubberConfig { cfg, c ->
      assert cfg == [
        version: "v4",
        base: "foo",
        variants: [
          foo: [:]
        ],
      ]

      c()
    }

    mockRunner.demand.build { variant, labels, context, excludes ->
      assert variant == "foovariant"
      assert labels == [foo: "foolabel", bar: "barlabel"]
      assert context == URI.create("foo/dir")
      assert excludes == [".git", "foo/docs/*"]

      "foo-image-id"
    }

    mockRunner.use {
      def ws = new WorkflowScript()
      def runner = new PipelineRunner(ws)

      stage.build(ws, runner)
    }

    assert stage.context["imageID"] == "foo-image-id"
  }

  void testDeploy_withTest() {
    def pipeline = new Pipeline("foo", [
      stages: [
        [
          name: "foo",
          build: "foovariant",
          publish: [
            image: true,
          ],
        ],
        [
          name: "bar",
          deploy: [
            image: '${foo.publishedImage}',
            chart: [
              name: 'foo',
            ],
            tag: '${.stage}-tag',
            test: true,
            overrides: [
              foo: [
                bar: 'x${foo.imageID}y',
                baz: 'otherthing',
              ],
            ],
          ],
        ],
      ]
    ])

    def mockRunner = new MockFor(PipelineRunner)
    def stack = pipeline.stack()
    def stage = stack[2][0]

    stubStageContexts(stack, [
      foo: { ctx ->
        ctx["imageID"] = "foo-image-id"

        ctx["imageName"] = "foo-project"
        ctx["imageFullName"] = "registry.example/bar/foo-project"
        ctx["imageTag"] = "0000-00-00-000000-foo"
        ctx["imageTags"] = ["0000-00-00-000000-foo"]
        ctx["publishedImage"] = "registry.example/bar/foo-project:0000-00-00-000000-foo"
      },
    ])

    mockRunner.demand.deployWithChart { chart, chartVersion, image, tag, overrides ->
      assert chart == "foo"
      assert chartVersion == ""
      assert image == "registry.example/bar/foo-project:0000-00-00-000000-foo"
      assert tag == "bar-tag"
      assert overrides == [foo: [bar: "xfoo-image-idy", baz: "otherthing"]]

      "bar-release-name"
    }

    mockRunner.demand.testRelease { releaseName ->
      assert releaseName == "bar-release-name"
    }

    mockRunner.use {
      def ws = new WorkflowScript()
      def runner = new PipelineRunner(ws)

      stage.deploy(ws, runner)
    }

    assert stage.context["releaseName"] == "bar-release-name"
  }

  void testDeploy_withoutTest() {
    def pipeline = new Pipeline("foo", [
      stages: [
        [
          name: "foo",
          build: "foovariant",
          publish: [
            image: true,
          ],
        ],
        [
          name: "bar",
          deploy: [
            image: '${foo.publishedImage}',
            chart: [
              name: 'foo',
            ],
            tag: '${.stage}-tag',
            test: false,
          ],
        ],
      ]
    ])

    def mockRunner = new MockFor(PipelineRunner)
    def stack = pipeline.stack()
    def stage = stack[2][0]

    stubStageContexts(stack, [
      foo: { ctx ->
        ctx["imageID"] = "foo-image-id"

        ctx["imageName"] = "foo-project"
        ctx["imageFullName"] = "registry.example/bar/foo-project"
        ctx["imageTag"] = "0000-00-00-000000-foo"
        ctx["imageTags"] = ["0000-00-00-000000-foo"]
        ctx["publishedImage"] = "registry.example/bar/foo-project:0000-00-00-000000-foo"
      },
    ])

    mockRunner.demand.deployWithChart { chart, version, image, tag, overrides ->
      assert chart == "foo"
      assert version == ""
      assert image == "registry.example/bar/foo-project:0000-00-00-000000-foo"
      assert tag == "bar-tag"
      assert overrides == [:]

      "bar-release-name"
    }

    mockRunner.use {
      def ws = new WorkflowScript()
      def runner = new PipelineRunner(ws)

      stage.deploy(ws, runner)
    }

    assert stage.context["releaseName"] == "bar-release-name"
  }

  void testExports() {
    def pipeline = new Pipeline("foo", [
      stages: [
        [
          name: "foo",
          build: "foovariant",
          exports: [
            thing: '${.imageID}-${setup.timestamp}',
          ],
        ],
      ]
    ])

    def mockRunner = new MockFor(PipelineRunner)
    def mockWS = new MockFor(WorkflowScript)
    def stack = pipeline.stack()
    def stage = stack[1][0]

    stubStageContexts(stack, [
      setup: { ctx ->
        ctx["timestamp"] = "0000-00-00-000000"
      },
      foo: { ctx ->
        ctx["imageID"] = "foo-image-id"
      },
    ])

    mockWS.use {
      mockRunner.use {
        def ws = new WorkflowScript()
        def runner = new PipelineRunner(ws)

        stage.exports(ws, runner)
      }
    }

    assert stage.context["thing"] == "foo-image-id-0000-00-00-000000"
  }

  void testPublish() {
    def pipeline = new Pipeline("foopipeline", [
      stages: [
        [
          name: "built",
          build: "foovariant",
        ],
        [
          name: "published",
          publish: [
            image: [
              id: '${built.imageID}',
            ],
          ],
        ]
      ]
    ])

    def mockRunner = new MockFor(PipelineRunner)
    def stack = pipeline.stack()
    def stage = stack[2][0]

    stubStageContexts(stack, [
      setup: { ctx ->
        ctx["project"] = "foo-project"
        ctx["timestamp"] = "0000-00-00-000000"
        ctx["imageLabels"] = []
      },
      built: { ctx ->
        ctx["imageID"] = "foo-image-id"
      },
    ])

    mockRunner.demand.registerAs { imageID, imageName, tag ->
      assert imageID == "foo-image-id"
      assert imageName == "foo-project"
      assert tag == "0000-00-00-000000-published"
    }

    mockRunner.demand.qualifyRegistryPath { imageName ->
      assert imageName == "foo-project"

      "registry.example/bar/foo-project"
    }

    mockRunner.use {
      def ws = new WorkflowScript()
      def runner = new PipelineRunner(ws)

      stage.publish(ws, runner)
    }

    assert stage.context["imageName"] == "foo-project"
    assert stage.context["imageFullName"] == "registry.example/bar/foo-project"
    assert stage.context["imageTag"] == "0000-00-00-000000-published"
    assert stage.context["imageTags"] == ["0000-00-00-000000-published"]
    assert stage.context["publishedImage"] == "registry.example/bar/foo-project:0000-00-00-000000-published"
  }

  void testPromote() {
    def pipeline = new Pipeline("foopipeline", [
      stages: [
        [
          name: "built",
          build: "foovariant",
        ],
        [
          name: "published",
          publish: [
            image: [
              id: '${built.imageID}',
            ],
          ],
        ],
        [
          name: "promoted",
          promote: [
            [
              chart: 'foochart',
              version: 'fooversion',
            ],
            [
              chart: 'foochart2',
              version: 'fooversion',
            ],
          ]
        ]
      ]
    ])

    def mockRunner = new MockFor(PipelineRunner)
    def mockWorkflow = new MockFor(WorkflowScript)
    def stack = pipeline.stack()
    def stage = stack[3][0]

    stubStageContexts(stack, [
      setup: { ctx ->
        ctx["project"] = "foo-project"
        ctx["timestamp"] = "0000-00-00-000000"
        ctx["imageLabels"] = []
      },
      built: { ctx ->
        ctx["imageID"] = "foo-image-id"
      },
      published: { ctx ->
        ctx["imageName"] = "foo-project"
        ctx["imageFullName"] = "registry.example/bar/foo-project"
        ctx["imageTag"] = "0000-00-00-000000-published"
        ctx["imageTags"] = ["0000-00-00-000000-published"]
        ctx["publishedImage"] = "registry.example/bar/foo-project:0000-00-00-000000-published"
      }
    ])

    mockWorkflow.demand.checkout {  }

    mockWorkflow.demand.sh { cmd ->
      assert cmd == "curl -Lo deployment-charts/.git/hooks/commit-msg http://gerrit.wikimedia.org/r/tools/hooks/commit-msg && chmod +x deployment-charts/.git/hooks/commit-msg"
    }

    mockWorkflow.demand.dir { directory, Closure c ->
      assert directory == 'deployment-charts'

      mockRunner.demand.updateCharts { config ->
        assert config[0].chart == 'foochart'
        assert config[0].version == 'fooversion'
        assert config[1].chart == 'foochart2'
        assert config[1].version == 'fooversion'
      }

      c()
    }
   
    mockRunner.use {
      mockWorkflow.use {
        def ws = new WorkflowScript()
        def runner = new PipelineRunner(ws)

        stage.promote(ws, runner)
      }
    }
  }

  void testRun() {
    def pipeline = new Pipeline("foo", [
      stages: [
        [
          name: "foo",
          build: "foovariant",
        ],
        [
          name: "bar",
          run: [
            image: '${foo.imageID}',
            arguments: ['${.stage}arg'],
            credentials: [
              [id: "foo", name: "bar"]
            ],
            env: [foo: "bar"],
            tail: 10,
          ],
        ],
      ]
    ])

    def mockRunner = new MockFor(PipelineRunner)
    def stack = pipeline.stack()
    def stage = stack[2][0]

    stubStageContexts(stack, [
      foo: { ctx ->
        ctx["imageID"] = "foo-image-id"
      },
    ])

    mockRunner.demand.run { image, args, envVars, creds, lines ->
      assert image == "foo-image-id"
      assert args == ["bararg"]
      assert envVars == [foo: "bar"]
      assert creds == [[id: "foo", name: "bar"]]
      assert lines == 10
    }

    mockRunner.use {
      def ws = new WorkflowScript()
      def runner = new PipelineRunner(ws)

      stage.run(ws, runner)
    }
  }

  private void stubStageContexts(stack, stubs) {
    stack.each { stages ->
      stages.each { stage ->
        stage.context["stage"] = stage.name

        stubs.each { stageName, stub ->
          if (stage.name == stageName) {
            stub(stage.context)
          }
        }
      }
    }
  }
}
