package org.wikimedia.integration

import com.cloudbees.groovy.cps.NonCPS

import static org.wikimedia.integration.Utility.merge
import static org.wikimedia.integration.Utility.timestampLabel

import org.wikimedia.integration.ExecutionContext
import org.wikimedia.integration.PatchSet
import org.wikimedia.integration.Pipeline

import groovy.json.JsonSlurperClassic

import java.net.URI

class PipelineStage implements Serializable {
  static final String SETUP = 'setup'
  static final String TEARDOWN = 'teardown'
  static final List STEPS = ['build', 'run', 'copy', 'publish', 'promote', 'deploy', 'exports', 'trigger']
  static final URI CHARTSREPO = URI.create('https://gerrit.wikimedia.org/r/operations/deployment-charts.git')

  /**
   * Image names must be prefixed by or match one of the following. Note that
   * context variables within these strings are substituted just prior to
   * enforcement.
   */
  static final List ALLOWED_IMAGE_PREFIXES = [
    '${setup.project}',
    '${setup.projectShortName}',
  ]

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
   *       credentials: [],
   *       tail: 0,
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
   *       credentials: [],
   *       tail: 0,
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
   *       credentials: [],
   *       tail: 0,
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
   *       version: "\${.imageTag}"
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
      dcfg.build.imagePullPolicy = dcfg.build.imagePullPolicy ?: 'always'
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

      // run.credentials defaults to []
      dcfg.run.credentials = dcfg.run.credentials ?: []
      dcfg.run.tail = dcfg.run.tail ?: 0
    }

    if (dcfg.copy) {
      dcfg.copy = dcfg.copy.collect { file ->
        switch (file) {
          // handle short-hand [source, ...] syntax
          case String:
          case GString:
            file = [source: file]
        }

        file = file.clone()

        // copy[].from defaults to this stage's container
        file.from = file.from ?: '${.container}'

        // copy[].destination defaults to the source
        file.destination = file.destination ?: file.source

        // by default do not archive copied files as artifacts
        file.archive = file.archive == null ? false : file.archive

        file
      }
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

        pcfg.image.tags = (pcfg.image.tags ?: ["\${${SETUP}.tag}"]).clone()
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

      dcfg.deploy.image = dcfg.deploy.image ?: '${.imageName}'
      dcfg.deploy.tag = dcfg.deploy.tag ?: '${.imageTag}'
      dcfg.deploy.cluster = dcfg.deploy.cluster ?: "ci"
      dcfg.deploy.test = dcfg.deploy.test == null ? true : dcfg.deploy.test
      dcfg.deploy.overrides = dcfg.deploy.overrides ?: [:]
      dcfg.deploy.timeout = dcfg.deploy.timeout ?: null

      if (dcfg.deploy.chart) {
        dcfg.deploy.chart = dcfg.deploy.chart.clone()
      } else {
        dcfg.deploy.chart = [:]
      }

      dcfg.deploy.chart.name = dcfg.deploy.chart.name ?: "\${${SETUP}.projectShortName}"
      dcfg.deploy.chart.version = dcfg.deploy.chart.version ?: ""
    }

    if (dcfg.notify) {
      dcfg.notify = dcfg.notify.clone()

      if (dcfg.notify.email) {
        dcfg.notify.email = dcfg.notify.email.clone()

        switch (dcfg.notify.email.to) {
          case String:
          case GString:
            dcfg.notify.email.to = [dcfg.notify.email.to]
        }

        dcfg.notify.email.subject = dcfg.notify.email.subject ?:
          "[Pipeline] \${${SETUP}.projectShortName} \${${SETUP}.pipeline}/\${.stage} failed"
        dcfg.notify.email.body = dcfg.notify.email.body ?: '''
          |Pipeline ${setup.pipeline} for project ${setup.project} failed during
          |stage ${.stage}. See the log for details: ${setup.logURL}
        '''.stripMargin('|').trim()
      }
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
   * Constructs and returns a closure for this pipeline stage using the given
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
        try {
          ws.echo("running steps in ${pipeline.directory} with config: ${config.inspect()}")

          ws.dir(pipeline.directory) {
            for (def stageStep in STEPS) {
              if (config[stageStep]) {
                ws.echo("step: ${stageStep}, config: ${config.inspect()}")
                this."${stageStep}"(ws, runner)
              }
            }
          }
        } catch (ex) {
          notify(ws, runner)
          throw ex
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

    if (config.build || config.run || config.copy) {
      labels.add("blubber")
    }

    if (config.publish?.image) {
      labels.add("dockerPublish")
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
   * <dt><code>${setup.pipeline}</code></dt>
   * <dd>Pipeline name.</dd>
   *
   * <dt><code>${setup.logURL}</code></dt>
   * <dd>URL to the build log.</dd>
   *
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
   * <dt><code>${setup.tag}</code></dt>
   * <dd>Newly created git tag if one has been created/pushed.</dd>
   *
   * <dt>(Additional job parameters and checkout variables)</dt>
   * <dd>In addition to the above, all job parameters and checkout variables
   * are included as well.</dd>
   * <dd>For example, if the job is configured with a parameter called "FOO",
   * the parameter value will be available as <code>${setup.FOO}</code>.</dd>
   * </dl>
   */
  void setup(ws, runner) {
    context["pipeline"] = pipeline.name
    context["logURL"] = "${ws.env.BUILD_URL}console"

    def imageLabels = [
      "jenkins.job": ws.env.JOB_NAME,
      "jenkins.build": ws.env.BUILD_ID,
    ]

    // include all job parameters in the setup context
    ws.params.each { k, v -> context["params.${k}"] = v }

    def gitRef
    def scm

    if (ws.params.ZUUL_REF) {
      def patchset = PatchSet.fromZuul(ws.params)
      scm = patchset.getSCM(pipeline.fetchOptions)
      gitRef = patchset.ref
      imageLabels["zuul.commit"] = patchset.commit
    } else {
      scm = ws.scm
      gitRef = ws.scm.branches[0].name
    }

    def gitInfo = ws.checkout(scm)
    context["project"] = URI.create(gitInfo.GIT_URL).path.replaceFirst('^/(r/)?', '')
    context["projectShortName"] = context["project"].substring(context["project"].lastIndexOf('/')+1)

    // include all returned checkout information, normalizing the names of
    // key items such as commit, branch, and remote URL
    context["commit"] = gitInfo.GIT_COMMIT
    context["branch"] = gitInfo.GIT_LOCAL_BRANCH
    context["remote"] = gitInfo.GIT_URL

    // include any bare tag name
    if (gitRef.startsWith("refs/tags/")) {
      context["tag"] = gitRef.substring("refs/tags/".length())
    } else {
      context["tag"] = ""
    }

    gitInfo.each { k, v -> context[k] = v }

    imageLabels["ci.project"] = context['project']
    imageLabels["ci.pipeline"] = pipeline.name

    context["timestamp"] = timestampLabel()
    context["imageLabels"] = imageLabels

    // provide an http proxy for use in pipeline configurations if configured
    if (runner.httpProxy) {
      context["httpProxy"] = runner.httpProxy
    }
  }

  /**
   * Performs teardown steps, removing images and helm releases, and reporting
   * back to Gerrit.
   */
  void teardown(ws, runner) {
    try {
      ws.echo "removing containers"
      runner.removeContainers(context.getAll("container").values())
    } catch (all) {}

    try {
      ws.echo "removing images"
      runner.removeImages(context.getAll("imageID").values())
    } catch (all) {}

    try {
      ws.echo "purging all releases"
      runner.purgeReleases(context.getAll("releaseName").values())
    } catch (all) {}

    def imageTags = context.getAll("imageTags")
    context.getAll("publishedImage").each { stageName, image ->
      ws.echo "reporting published image ${image} to gerrit"
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
   *
   *     <dt><code>excludes</code></dt>
   *     <dd>Patterns of files/directories to exclude from the build context.
   *     Providing this will result in any local <code>.dockerignore</code>
   *     file being overwritten prior to the build and only has an effect when
   *     the context is a local directory. Patterns must be <a
   *     href="https://docs.docker.com/engine/reference/builder/#dockerignore-file">valid
   *     Docker ignore rules</a>.</dd>
   *
   *     <dt><code>imagePullPolicy</code></dt>
   *     <dd>Must be <code>"always"</code> or <code>"never"</code>.  
   *      If <code>"always"</code>, --pull will be passed to <code>docker build</code>.</dd>
   *     <dd>Default: <code>"always"</code></dd>
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
   *
   * <dt><code>${[stage].imageLocalName}</code></dt>
   * <dd>Randomly assigned image name for use within subsequent steps or
   * stages where an ID is insufficient. For example, if you want to build
   * another image that references this one as a base image, you must
   * reference the name not an ID.</dd>
   * </dl>
   */
  void build(ws, runner) {
    runner.withBlubberConfig(context % (config.blubberfile ?: pipeline.blubberfile)) {
      context["imageID"] = runner.build(
        context % config.build.variant,
        context["setup.imageLabels"],
        URI.create(context % config.build.context),
        context % config.build.excludes,
        context % config.build.imagePullPolicy
      )
    }

    context["imageLocalName"] = runner.assignLocalName(context["imageID"])
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
   *
   *     <dt><code>tail</code></dt>
   *     <dd>Save this many lines of trailing output as
   *     <code>${[stage].output}</code>.</dd>
   *     <dd>Default: <code>0</code></dd>
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
   *
   * <h3>Exports</h3>
   * <dl>
   * <dt><code>${[stage].output}</code></dt>
   * <dd>If <code>tail</code>is specified, that number of trailing
   * lines from the output (stdout) of container process.</dd>
   * </dl>
   */
  void run(ws, runner) {
    def result = runner.run(
      context % config.run.image,
      context % config.run.arguments,
      context % config.run.env,
      config.run.credentials,
      config.run.tail,
    )

    context["container"] = result.container
    context["output"] = result.output
  }

  /**
   * Copy files from the filesystem of a previously run container.
   *
   * <h3>Configuration</h3>
   * <dl>
   * <dt><code>copy</code></dt>
   * <dd>List of files/directories to copy from previously run variants to the
   * local build context.</dd>
   * <dd>Default: <code>[]</code></dd>
   * <dd>
   *   <dl>
   *     <dt>from</dt>
   *     <dd>Stopped container from which to copy files</dd>
   *     <dd>Default: <code>${.container}</code> (the container that ran
   *     during this stage's run step)</dd>
   *
   *     <dt>source</dt>
   *     <dd>Globbed file path resolving any number of files relative to the
   *     container's root (/) directory.</dd>
   *
   *     <dt>destination</dt>
   *     <dd>Destination file path relative to the local context</dd>
   *     <dd>Default: <code>source</code> (the source path made relative to
   *     the local context directory).</dd>
   *
   *     <dt>archive</dt>
   *     <dd>Whether to archive the destination file as a Jenkins build
   *     artifact.</dd>
   *     <dd>Default: <code>false</code></dd>
   *   </dl>
   * </dd>
   * <dd>Shorthand: <code>[source, source, ...]</code></dd>
   * </dl>
   *
   * <h3>Exports</h3>
   * <dl>
   * <dt><code>${[stage].artifactURLs}</code></dt>
   * <dd>Jenkins build artifact URLs, one per line. If the
   * <code>destination</code> of the copy was a directory, the artifact at
   * this URL will be a tar.gz file.</dd>
   * </dl>
   */
  void copy(ws, runner) {
    if (config.copy) {
      def artifactURLs = (context % config.copy).collect {
        runner.copyFilesFrom(it.from, it.source, it.destination, it.archive)
      }
      context["artifactURLs"] = artifactURLs.join("\n")
    }
  }

  /**
   * Push a built image variant to the WMF Docker registry.
   *
   * <h3>Configuration</h3>
   * <dl>
   * <dt><code>publish</code></dt>
   * <dd>
   *   <dl>
   *     <dt><code>image</code></dt>
   *     <dd>Publish an image to the WMF Docker registry</dd>
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
   *         <dd>Default: <code>[${setup.tag}]</code> The git tag if one has
   *         been pushed. Otherwise, nothing.</dd>
   *       </dl>
   *     </dd>
   *   </dl>
   * </dd>
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
      def imageName = sanitizeImageRef(context % publishImage.name)
      def imageTags = ([context % publishImage.tag] + (context % publishImage.tags)).collect {
        sanitizeImageRef(it)
      }

      // enforce image name prefixes, allowing either exact matches or
      // '<prefix>-*'
      def allowedPrefixes = []
      def nameIsAllowed = ALLOWED_IMAGE_PREFIXES.any { prefix ->
        def allowedPrefix = sanitizeImageRef(context % prefix)
        allowedPrefixes += allowedPrefix
        imageName == allowedPrefix || imageName.startsWith("${allowedPrefix}-")
      }

      if (!nameIsAllowed) {
        throw new RuntimeException(
          'the published image name (`' + imageName + '`) must either match ' +
          'or be prefixed by the full or short project name ' +
          '(`' + allowedPrefixes.join('` or `') + '`)'
        )
      }

      // omit empty strings
      imageTags.removeAll { !it }

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
  }

  List getReviewers(ws) {
    def changeResponse = ws.httpRequest(url: "https://gerrit.wikimedia.org/r/changes/${ws.params.ZUUL_CHANGE}/detail",
                                        httpMode: "GET",
                                        customHeaders: [[name: "content-type", value: 'application/json']],
                                        consoleLogResponseBody: false,
                                        validResponseCodes: "200")

    //remove the magic prefix line preventing XSSI attacks before parsing
    def change = changeResponse.content.split("\\n")[1]
    def changeInfo = parseJson(change)

    def plusTwoer = changeInfo?."labels"?."Code-Review"?.approved?._account_id
    def author = changeInfo?.owner?._account_id

    // no bots allowed!
    if (author && hasServiceUserTag(changeInfo.owner.tags)) {
      author = null
    }

    if (plusTwoer && hasServiceUserTag(changeInfo?."labels"?."Code-Review"?.approved?.tags)) {
      plusTwoer = null
    } else if (!plusTwoer && !hasServiceUserTag(changeInfo.submitter.tags)) {
      // a merge without +2 is also possible? Check the submitter's info in absence of code review
      plusTwoer = changeInfo?.submitter?._account_id
    }

    def reviewers = [author, plusTwoer].unique(false)
    reviewers.removeAll([null])

    return reviewers
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

      def reviewers = getReviewers(ws)

      //check out the repo
      ws.checkout(new PatchSet(
        commit: "master",
        ref: "refs/heads/master",
        remote: CHARTSREPO
      ).getSCM([target: 'deployment-charts']))

      ws.dir('deployment-charts') {
        // Ensure that the Change-Id commit-msg hook is installed and enabled
        // for this repo regardless of the Jenkins hooks setting. Note that
        // Jenkins by default sets the hooksPath to /dev/null to disable them
        ws.sh("git config --unset core.hooksPath")
        ws.sh("curl -Lo .git/hooks/commit-msg https://gerrit.wikimedia.org/r/tools/hooks/commit-msg && chmod +x .git/hooks/commit-msg")

        runner.updateCharts(context % config.promote, reviewers)
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
   *     <dd>
   *       <dl>
   *         <dt>name</dt>
   *         <dd>Chart name from the {@link PipelineRunner#chartRepository}.</dd>
   *         <dd>Default: <code>${setup.projectShortName}</code></dd>
   *
   *         <dt>version</dt>
   *         <dd>Chart version.</dd>
   *         <dd>Default: <code>${.imageTag}</code> (The primary tag of image
   *         built in this stage.)</dd>
   *       </dl>
   *     </dd>
   *
   *     <dt>tag</dt>
   *     <dd>Tag of the registered image to deploy</dd>
   *     <dd>Default: <code>${.imageTag}</code></dd>
   *
   *     <dt>test</dt>
   *     <dd>Whether to run <code>helm test</code> against this deployment</dd>
   *     <dd>Default: <code>true</code></dd>
   *
   *     <dt>timeout</dt>
   *     <dd>Timeout for <code>helm install</code></dd>
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
      context % config.deploy.timeout,
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

  /**
   * Notifies folks of stage failure according to the stage or pipeline level
   * configuration.
   *
   * <h3>Configuration</h3>
   * <dl>
   * <dt><code>notify</code></dt>
   * <dd>Notify recipients in the event this stage fails.
   * <dd>
   *   <dl>
   *     <dt>email</dt>
   *     <dd>Email a number of recipients.</dd>
   *     <dd>
   *       <dl>
   *       <dt>to</dt>
   *       <dd>List of email recipients.</dd>
   *
   *       <dt>subject</dt>
   *       <dd>Email subject line.</dd>
   *       <dd>Default: <code>[Pipeline] ${setup.projectShortName} ${setup.pipeline}/${.stage} failed</code></dd>
   *
   *       <dt>body</dt>
   *       <dd>Email body.</dd>
   *       <dd>Default: A short message indicating which stage failed and a link
   *       to the build log URL.</dd>
   *       </dl>
   *     </dd>
   *   </dl>
   * </dd>
   * </dl>
   */
  void notify(ws, runner) {
    if (config.notify || pipeline.notify) {
      def notify = merge(pipeline.notify, config.notify)

      if (notify.email) {
        ws.mail(
          to: (context % notify.email.to).join(','),
          subject: context % notify.email.subject,
          body: context % notify.email.body
        )
      }
    }
  }

  /**
   * Trigger a downstream job on the local Jenkins server
   *
   * <h3>Configuration</h3>
   * <dl>
   * <dt><code>trigger</code></dt>
   * <dd>
   *   <dl>
   *     <dt>name</dt>
   *     <dd>Name of the downstream job</dd>
   *     <dt>parameters</dt>
   *     <dd>Optional: Object specifying parameters to pass to the job</dd>
   *     <dt>progagate</dt>
   *     <dd>Optional: Boolean. If <code>true</code> (the default), then the result of this
   *         step is that of the downstream build (e.g., success, unstable, failure,
   *         not built, or aborted). If <code>false</code>, then this step succeeds
   *         even if the downstream build is unstable, failed, etc.
   *     </dd>
   *     <dt>wait</dt>
   *     <dd>Optional: Boolean. If <code>true</code>, wait for completion of the downstream
   *         build before completing this step.  Default: <code>false></code>
   *     </dd>
   *   </dl>
   * </dd>
   *
   */
  void trigger(ws, runner) {
    def jobname = config.trigger.name

    if (jobname) {
      def params = config.trigger.parameters.collect { key, val -> [$class: 'StringParameterValue', name: key, value: context % val ] }

      def propagate = (config.trigger.propagate == false ? false : true)
      def wait = (config.trigger.wait == true ? true : false)

      // Xref: https://www.jenkins.io/doc/pipeline/steps/pipeline-build-step/
      runner.triggerJob([
        job: jobname,
        parameters: params,
        propagate: propagate,
        wait: wait,
      ])
    }
  }

  private

  String sanitizeImageRef(name) {
    name.toLowerCase().replaceAll('/', '-')
  }

  @NonCPS
  def parseJson(jsonString) {
    def jsonSlurper = new JsonSlurperClassic()

    jsonSlurper.parseText(jsonString)
  }

  Boolean hasServiceUserTag(tags) {
    if(tags && tags.contains("SERVICE_USER")){
      return true;
    }

    return false;
  }

}
