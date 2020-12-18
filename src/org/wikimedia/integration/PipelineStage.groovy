package org.wikimedia.integration

import com.cloudbees.groovy.cps.NonCPS

import static org.wikimedia.integration.Utility.timestampLabel

import org.wikimedia.integration.ExecutionContext
import org.wikimedia.integration.PatchSet
import org.wikimedia.integration.Pipeline

import java.net.URI

class PipelineStage implements Serializable {
  static final String SETUP = 'setup'
  static final String TEARDOWN = 'teardown'
  static final List STEPS = ['build', 'run', 'publish', 'promote', 'deploy', 'exports']
  static final URI CHARTSREPO = URI.create('https://gerrit.wikimedia.org/r/operations/deployment-charts.git')

  Pipeline pipeline
  String name
  Map config

  private def context

  /**
   * Returns an config based on the given one but with default values
   * inserted.
   *
   * @example Shorthand stage config (providing only a stage name)
   * <pre><code>
   *   def cfg = [name: "foo"]
   *
   *   assert PipelineStage.defaultConfig(cfg) == [
   *     name: "foo",
   *     build: [
   *       variant: '${.stage}', // builds a variant by the same name
   *     ],
   *     run: [
   *       image: '${.imageID}', // runs the variant built by this stage
   *       arguments: [],
   *     ],
   *   ]
   * </code></pre>
   *
   * @example Shorthand stage config (providing only a stage name)
   * <pre><code>
   *   def cfg = [name: "foo"]
   *
   *   assert PipelineStage.defaultConfig(cfg) == [
   *     name: "foo",
   *     build: '${.stage}',     // builds a variant by the same name
   *     run: [
   *       image: '${.imageID}', // runs the variant built by this stage
   *       arguments: [],
   *     ],
   *   ]
   * </code></pre>
   *
   * @example Configuring `run: true` means run the variant built by this
   * stage
   * <pre><code>
   *   def cfg = [name: "foo", build: "foo", run: true]
   *
   *   assert PipelineStage.defaultConfig(cfg) == [
   *     name: "foo",
   *     build: "foo",
   *     run: [
   *       image: '${.imageID}', // runs the variant built by this stage
   *       arguments: [],
   *     ],
   *   ]
   * </code></pre>
   *
   * @example Publish image default configuration
   * <pre><code>
   *   def cfg = [image: true]
   *   def defaults = PipelineStage.defaultConfig(cfg)
   *
   *   // publish.image.id defaults to the previously built image
   *   assert defaults.publish.image.id == '${.imageID}'
   *
   *   // publish.image.name defaults to the project name
   *   assert defaults.publish.image.name == '${setup.project}'
   *
   *   // publish.image.tag defaults to {timestamp}-{stage name}
   *   assert defaults.publish.image.tag == '${setup.timestamp}-${.stage}'
   * </code></pre>
   *
   * @example Promote a published image
   * <pre><code>
   *   def cfg = [promote: true]
   *   def defaults = PipelineStage.defaultConfig(cfg)
   *
   *   // promote defaults to a map chart, environments, and version.
   *   // The default chart is the project name. The default environments
   *   // are the empty list. The default version is the published inmage tag.
   *   assert defaults == [
   *     [
   *       chart: '${setup.project}',
   *       environments: []
   *       version: "\${/imageTag}"
   *     ]
   *   ]
   * </code></pre>
   */
  @NonCPS
  static Map defaultConfig(Map cfg) {
    Map dcfg

    // shorthand with just name is: build and run a variant
    if (cfg.size() == 1 && cfg["name"]) {
      dcfg = cfg + [
        build: [
          variant: '${.stage}',
        ],
        run: [
          image: '${.imageID}',
        ]
      ]
    } else {
      dcfg = cfg.clone()
    }

    if (dcfg.build) {
      if (dcfg.build instanceof String || dcfg.build instanceof GString) {
        dcfg.build = [
          variant: dcfg.build,
        ]
      }

      dcfg.build.variant = dcfg.build.variant ?: '${.stage}'
      dcfg.build.context = dcfg.build.context ?: '.'
    }

    if (dcfg.run) {
      // run: true means run the built image
      if (dcfg.run == true) {
        dcfg.run = [
          image: '${.imageID}',
        ]
      } else {
        dcfg.run = dcfg.run.clone()
      }

      // run.image defaults to previously built image
      dcfg.run.image = dcfg.run.image ?: '${.imageID}'

      // run.arguments defaults to []
      dcfg.run.arguments = dcfg.run.arguments ?: []

      // run.env defaults to [:]
      dcfg.run.env = dcfg.run.env ?: [:]

      // run.credentials defaults to [:]
      dcfg.run.credentials = dcfg.run.credentials ?: [:]
    }

    if (dcfg.publish) {
      def pcfg = dcfg.publish.clone()

      if (pcfg.image) {
        if (pcfg.image == true) {
          pcfg.image = [:]
        } else {
          pcfg.image = pcfg.image.clone()
        }

        // publish.image.id defaults to the previously built image
        pcfg.image.id = pcfg.image.id ?: '${.imageID}'

        // publish.image.name defaults to the project name
        pcfg.image.name = pcfg.image.name ?: "\${${SETUP}.project}"

        // publish.image.tag defaults to {timestamp}-{stage name}
        pcfg.image.tag = pcfg.image.tag ?: "\${${SETUP}.timestamp}-\${.stage}"

        pcfg.image.tags = (pcfg.image.tags ?: []).clone()
      }

      if (pcfg.files) {
        pcfg.files.paths = pcfg.files.paths.clone()
      }

      dcfg.publish = pcfg
    }

    if (dcfg.promote) {
      if (dcfg.promote == true) {
        dcfg.promote = [
          [
            chart: "\${${SETUP}.projectShortName}",
            environments: [],
            version: '${.imageTag}',
          ]
        ]
      } else {
        dcfg.promote = dcfg.promote.clone()

        dcfg.promote.each{
            it.chart = it.chart ?: "\${${SETUP}.projectShortName}"
            it.environments = it.environments ?: []
            it.version = it.version ?: '${.imageTag}'
        }
      }
    }

    if (dcfg.deploy) {
      dcfg.deploy = dcfg.deploy.clone()

      dcfg.deploy.image = dcfg.deploy.image ?: '${.publishedImage}'
      dcfg.deploy.cluster = dcfg.deploy.cluster ?: "ci"
      dcfg.deploy.test = dcfg.deploy.test == null ? true : dcfg.deploy.test
      dcfg.deploy.overrides = dcfg.deploy.overrides ?: [:]

      if (dcfg.deploy.chart) {
        dcfg.deploy.chart = dcfg.deploy.chart.clone()
      } else {
        dcfg.deploy.chart = [:]
      }

      dcfg.deploy.chart.name =  dcfg.deploy.chart.name ?: "\${${SETUP}.projectShortName}"
      dcfg.deploy.chart.version = dcfg.deploy.chart.version ?: ""
    }

    dcfg
  }

  PipelineStage(Pipeline pline, String stageName, Map stageConfig, nodeContext) {
    pipeline = pline
    name = stageName
    config = stageConfig
    context = nodeContext
  }

  /**
   * Constructs and retruns a closure for this pipeline stage using the given
   * Jenkins workflow script object.
   */
  Closure closure(ws) {
    ({
      def runner = pipeline.runner(ws)

      context["stage"] = name

      switch (name) {
      case SETUP:
        setup(ws, runner)
        break
      case TEARDOWN:
        teardown(ws, runner)
        break
      default:
        ws.echo("running steps in ${pipeline.directory} with config: ${config.inspect()}")

        ws.dir(pipeline.directory) {
          for (def stageStep in STEPS) {
            if (config[stageStep]) {
              ws.echo("step: ${stageStep}, config: ${config.inspect()}")
              this."${stageStep}"(ws, runner)
            }
          }
        }
      }

      def exports = context.getAll()
      ws.echo "stage ${name} completed. exported: ${exports.inspect()}"
    })
  }

  /**
   * Returns a set of node labels that will be required for this stage to
   * function correctly.
   */
  Set getRequiredNodeLabels() {
    def labels = [] as Set

    if (config.build || config.run) {
      labels.add("blubber")
    }

    if (config.publish) {
      if (config.publish.files) {
        labels.add("blubber")
      }

      if (config.publish.image) {
        labels.add("dockerPublish")
      }
    }

    if (config.promote) {
      labels.add("chartPromote")
    }

    labels
  }

  /**
   * Performs setup steps, checkout out the repo and binding useful values to
   * be used by all other stages (default image labels, project identifier,
   * timestamp, etc).
   *
   * <h3>Exports</h3>
   * <dl>
   * <dt><code>${setup.project}</code></dt>
   * <dd>ZUUL_PROJECT parameter value if getting a patchset from Zuul.</dd>
   * <dd>Jenkins JOB_NAME value otherwise.</dd>
   *
   * <dl>
   * <dt><code>${setup.projectShortName}</code></dt>
   * <dd>The string after the last forward slash in the ZUUL_PROJECT parameter if getting a patchset from Zuul.</dd>
   * <dd>The string after the last forward slash in the Jenkins JOB_NAME value otherwise.</dd>
   *
   * <dt><code>${setup.timestamp}</code></dt>
   * <dd>Timestamp at the start of pipeline execution. Used in image tags, etc.</dd>
   *
   * <dt><code>${setup.imageLabels}</code></dt>
   * <dd>Default set of image labels:
   *    <code>jenkins.job</code>,
   *    <code>jenkins.build</code>,
   *    <code>ci.project</code>,
   *    <code>ci.pipeline</code>
   * </dd>
   *
   * <dt><code>${setup.commit}</code></dt>
   * <dd>Git commit SHA of the checked out patchset.</dd>
   *
   * <dt><code>${setup.branch}</code></dt>
   * <dd>Git branch of the checked out patchset.</dd>
   *
   * <dt><code>${setup.remote}</code></dt>
   * <dd>Git remote URL of the checked out patchset.</dd>
   *
   * <dt>(Additional job parameters and checkout variables)</dt>
   * <dd>In addition to the above, all job parameters and checkout variables
   * are included as well.</dd>
   * <dd>For example, if the job is configured with a parameter called "FOO",
   * the parameter value will be available as <code>${setup.FOO}</code>.</dd>
   * </dl>
   */
  void setup(ws, runner) {
    def imageLabels = [
      "jenkins.job": ws.env.JOB_NAME,
      "jenkins.build": ws.env.BUILD_ID,
    ]

    // include all job parameters in the setup context
    ws.params.each { k, v -> context[k] = v }

    def gitInfo = [:]

    if (ws.params.ZUUL_REF) {
      def patchset = PatchSet.fromZuul(ws.params)
      gitInfo = ws.checkout(patchset.getSCM(pipeline.fetchOptions))
      context["project"] = patchset.project.replaceAll('/', '-')
      imageLabels["zuul.commit"] = patchset.commit

      def splitProject = patchset.project.split('/')
      context["projectShortName"] = splitProject[splitProject.size() - 1]

    } else {
      gitInfo = ws.checkout(ws.scm)
      context["project"] = ws.env.JOB_NAME
      context["projectShortName"] = ws.env.JOB_NAME
    }

    // include all returned checkout information, normalizing the names of
    // key items such as commit, branch, and remote URL
    context["commit"] = gitInfo.GIT_COMMIT
    context["branch"] = gitInfo.GIT_BRANCH
    context["remote"] = gitInfo.GIT_URL
    gitInfo.each { k, v -> context[k] = v }

    imageLabels["ci.project"] = context['project']
    imageLabels["ci.pipeline"] = pipeline.name

    context["timestamp"] = timestampLabel()
    context["imageLabels"] = imageLabels
  }

  /**
   * Performs teardown steps, removing images and helm releases, and reporting
   * back to Gerrit.
   */
  void teardown(ws, runner) {
    try {
      runner.removeImages(context.getAll("imageID").values())
    } catch (all) {}

    try {
      runner.purgeReleases(context.getAll("releaseName").values())
    } catch (all) {}

    def imageTags = context.getAll("imageTags")
    context.getAll("publishedImage").collect { stageName, image ->
      try {
        runner.reportImageToGerrit(image, imageTags[stageName] ?: [])
      } catch (all) {}
    }
  }

  /**
   * Builds the configured Blubber variant.
   *
   * <h3>Configuration</h3>
   * <dl>
   * <dt><code>build</code></dt>
   * <dd>Blubber variant name and build context options</dd>
   * <dd>Specifying <code>build: "foo"</code> expands to
   *   <code>build: { variant: "foo", context: "." }</code></dd>
   * <dd>
   *   <dl>
   *     <dt><code>variant</code></dt>
   *     <dd>Blubber variant to build</dd>
   *     <dd>Default: <code>{$.stage}</code> (same as the stage name)</dd>
   *
   *     <dt><code>context</code></dt>
   *     <dd>Build context directory or URL <a
   *     href="https://docs.docker.com/engine/reference/commandline/build/">supported
   *     by <code>docker build</code></a></dd>
   *     <dd>Default: <code>"."</code></dd>

   *     <dt><code>excludes</code></dt>
   *     <dd>Patterns of files/directories to exclude from the build context.
   *     Providing this will result in any local <code>.dockerignore</code>
   *     file being overwritten prior to the build and only has an effect when
   *     the context is a local directory. Patterns must be <a
   *     href="https://docs.docker.com/engine/reference/builder/#dockerignore-file">valid
   *     Docker ignore rules</a>.</dd>
   *   </dl>
   * </dd>
   * </dl>
   *
   * <h3>Example</h3>
   * <pre><code>
   *   stages:
   *     - name: candidate
   *       build: production
   * </code></pre>
   *
   * <h3>Example</h3>
   * <pre><code>
   *   stages:
   *     - name: build-main-project
   *       build:
   *         variant: build
   *         excludes: [/src/foo]
   *     - name: build-subproject
   *       build:
   *         variant: build-foo
   *         context: src/foo
   * </code></pre>
   *
   * <h3>Exports</h3>
   * <dl>
   * <dt><code>${[stage].imageID}</code></dt>
   * <dd>Image ID of built image.</dd>
   * </dl>
   */
  void build(ws, runner) {
    def imageID = runner.build(
      context % config.build.variant,
      context["setup.imageLabels"],
      URI.create(context % config.build.context),
      context % config.build.excludes
    )

    context["imageID"] = imageID
  }

  /**
   * Runs the entry point of a built image variant.
   *
   * <h3>Configuration</h3>
   * <dl>
   * <dt><code>run</code></dt>
   * <dd>Image to run and entry-point arguments</dd>
   * <dd>Specifying <code>run: true</code> expands to
   *   <code>run: { image: '${.imageID}' }</code>
   *   (i.e. the image built in this stage)</dd>
   * <dd>
   *   <dl>
   *     <dt><code>image</code></dt>
   *     <dd>An image to run</dd>
   *     <dd>Default: <code>{$.imageID}</code></dd>
   *
   *     <dt><code>arguments</code></dt>
   *     <dd>Entry-point arguments</dd>
   *     <dd>Default: <code>[]</code></dd>
   *   </dl>
   * </dd>
   * </dl>
   *
   * <h3>Example</h3>
   * <pre><code>
   *   stages:
   *     - name: test
   *       build: test
   *       run: true
   * </code></pre>
   *
   * <h3>Example</h3>
   * <pre><code>
   *   stages:
   *     - name: built
   *     - name: lint
   *       run:
   *         image: '${built.imageID}'
   *         arguments: [lint]
   *     - name: test
   *       run:
   *         image: '${built.imageID}'
   *         arguments: [test]
   *         env:
   *           - MY_VAR: 'Hello'
   *         credentials:
   *           - id: 'sonarid'
   *             name: 'SONAR_API_KEY'
   * </code></pre>
   */
  void run(ws, runner) {
    runner.run(
      context % config.run.image,
      config.run.arguments.collect { context % it },
      config.run.env,
      config.run.credentials.collectEntries { it -> [it.id, it.name]},
    )
  }

  /**
   * Publish artifacts, either files or a built image variant (pushed to the
   * WMF Docker registry).
   *
   * <h3>Configuration</h3>
   * <dl>
   * <dt><code>publish</code></dt>
   * <dd>
   *   <dl>
   *     <dt><code>image</code></dt>
   *     <dd>Publish an to the WMF Docker registry</dd>
   *     <dd>
   *       <dl>
   *         <dt>id</dt>
   *         <dd>ID of a previously built image variant</dd>
   *         <dd>Default: <code>${.imageID}</code> (image built in this stage)</dd>
   *
   *         <dt>name</dt>
   *         <dd>Published name of the image. Note that this base name will be
   *         prefixed with the globally configured registry/repository name
   *         before being pushed.</dd>
   *         <dd>Default: <code>${setup.project}</code> (project identifier;
   *         see {@link setup()})</dd>
   *
   *         <dt>tag</dt>
   *         <dd>Primary tag under which the image is published</dd>
   *         <dd>Default: <code>${setup.timestamp}-${.stage}</code></dd>
   *
   *         <dt>tags</dt>
   *         <dd>Additional tags under which to publish the image</dd>
   *       </dl>
   *     </dd>
   *   </dl>
   * </dd>
   * <dd>
   *   <dl>
   *     <dt><code>files</code></dt>
   *     <dd>Extract and save files from a previously built image variant</dd>
   *     <dd>
   *       <dl>
   *         <dt>paths</dt>
   *         <dd>Globbed file paths resolving any number of files under the
   *         image's root filesystem</dd>
   *       </dl>
   *     </dd>
   *   </dl>
   * </dd>
   * </dl>
   *
   * <h3>Exports</h3>
   * <dl>
   * <dt><code>${[stage].imageName}</code></dt>
   * <dd>Short name under which the image was published</dd>
   *
   * <dt><code>${[stage].imageFullName}</code></dt>
   * <dd>Fully qualified name (registry/repository/imageName) under which the
   * image was published</dd>
   *
   * <dt><code>${[stage].imageTag}</code></dt>
   * <dd>Primary tag under which the image was published</dd>
   *
   * <dt><code>${[stage].publishedImage}</code></dt>
   * <dd>Full qualified name and tag (<code>${.imageFullName}:${.imageTag}</code>)</dd>
   * </dl>
   */
  void publish(ws, runner) {
    if (config.publish.image) {
      def publishImage = config.publish.image

      def imageID = context % publishImage.id
      def imageName = context % publishImage.name
      def imageTags = ([publishImage.tag] + publishImage.tags).collect { context % it }

      for (def tag in imageTags) {
        runner.registerAs(
          imageID,
          imageName,
          tag,
        )
      }

      context["imageName"] = imageName
      context["imageFullName"] = runner.qualifyRegistryPath(imageName)
      context["imageTag"] = context % publishImage.tag
      context["imageTags"] = imageTags
      context["publishedImage"] = context % '${.imageFullName}:${.imageTag}'
    }

    if (config.publish.files) {
      // TODO
    }
  }

/**
   * Promote a published artifact (create a patchset to change the image version
   * in the deployment-charts repo).
   *
   * <h3>Configuration</h3>
   * <dl>
   * <dt><code>promote</code></dt>
   * <dd>
   *   <dl>
   *     <dt>chart</dt>
   *     <dd>chart names to update</dd>
   *     <dd>Default: <code>[${setup.project}]</code> (project identifier;
   *     see {@link setup()})</dd>
   *
   *     <dt>environments</dt>
   *     <dd>List of environments to update</dd>
   *     <dd>Default: <code>[]</code></dd>
   *    </dl>
   * </dd>
   * </dl>
  */

  void promote(ws, runner) {
    //only promote if an image is published
    if (config.promote) {
      //check out the repo
      ws.checkout(new PatchSet(
        commit: "master",
        ref: "refs/heads/master",
        remote: CHARTSREPO
      ).getSCM([target: 'deployment-charts']))

      ws.sh("curl -Lo deployment-charts/.git/hooks/commit-msg http://gerrit.wikimedia.org/r/tools/hooks/commit-msg && chmod +x deployment-charts/.git/hooks/commit-msg")

      ws.dir('deployment-charts') {
        runner.updateCharts(context % config.promote)
      }
    }
  }

  /**
   * Deploy a published image to a WMF k8s cluster. (Currently only the "ci"
   * cluster is supported for testing.)
   *
   * <h3>Configuration</h3>
   * <dl>
   * <dt><code>deploy</code></dt>
   * <dd>
   *   <dl>
   *     <dt>image</dt>
   *     <dd>Reference to a previously published image</dd>
   *     <dd>Default: <code>${.publishedImage}</code> (image published in the
   *     {@link publish() publish step} of this stage)</dd>
   *
   *     <dt>cluster</dt>
   *     <dd>Cluster to target</dd>
   *     <dd>Default: <code>"ci"</code></dd>
   *     <dd>Currently only "ci" is supported and this configuration is
   *     effectively ignored</dd>
   *
   *     <dt>chart</dt>
   *     <dd>URL of Helm chart to use for deployment</dd>
   *     <dd>Required</dd>
   *
   *     <dt>test</dt>
   *     <dd>Whether to run <code>helm test</code> against this deployment</dd>
   *     <dd>Default: <code>true</code></dd>
   *
   *     <dt>overrides</dt>
   *     <dd>Additional values provided to the Helm chart.</dd>
   *     <dd>Default: none</dd>
   *   </dl>
   * </dd>
   * </dl>
   *
   * <h3>Exports</h3>
   * <dl>
   * <dt><code>${[stage].releaseName}</code></dt>
   * <dd>Release name of new deployment</dd>
   * </dl>
   */
  void deploy(ws, runner) {
    def release = runner.deployWithChart(
      context % config.deploy.chart.name,
      context % config.deploy.chart.version,
      context % config.deploy.image,
      context % config.deploy.tag,
      context % config.deploy.overrides,
    )

    context["releaseName"] = release

    if (config.deploy.test) {
      runner.testRelease(release)
    }
  }

  /**
   * Binds a number of new values for reference in subsequent stages.
   *
   * <h3>Configuration</h3>
   * <dl>
   * <dt><code>exports</code></dt>
   * <dd>Name/value pairs for additional exports.</dd>
   * </dl>
   *
   * <h3>Example</h3>
   * <pre><code>
   *   stages:
   *     - name: candidate
   *       build: production
   *       exports:
   *         image: '${.imageID}'
   *         tag: '${.imageTag}-my-tag'
   *     - name: published
   *       publish:
   *         image:
   *           id: '${candidate.image}'
   *           tags: ['${candidate.tag}']
   * </code></pre>
   *
   * <h3>Exports</h3>
   * <dl>
   * <dt><code>${[name].[value]}</code></dt>
   * <dd>Each configured name/value pair.</dd>
   * </dl>
   */
  void exports(ws, runner) {
    config.exports.each { export, value ->
      context[export] = context % value
    }
  }
}
