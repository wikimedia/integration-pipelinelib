package org.wikimedia.integration

import com.cloudbees.groovy.cps.NonCPS

import java.io.FileNotFoundException
import java.net.URI

import static org.wikimedia.integration.Utility.*

import org.wikimedia.integration.GerritPipelineComment
import org.wikimedia.integration.GerritReview
import org.wikimedia.integration.PipelineCredentialManager

/**
 * Provides an interface to common pipeline build/run/deploy functions.
 *
 * You must provide the Jenkins workflow script sandbox object that will be
 * used to declare Jenkins pipeline steps.
 *
 * {@code
 * // With just the Jenkins context
 * def pipeline = new PipelineRunner(this)
 *
 * // Or with a map of additional settings
 * def pipeline = new PipelineRunner(this, configPath: "dist/pipeline", registry: "foo.registry")
 * }
 */
class PipelineRunner implements Serializable {
  /**
   * Relative path to Blubber config file.
   */
  def blubberConfig = "blubber.yaml"

  /**
   * Image ref of the buildkit frontend to use during builds.
   */
  def buildkitFrontend = "docker-registry.wikimedia.org/wikimedia/blubber-buildkit:v0.11.1"

  /**
   * Directory in which pipeline configuration is stored.
   */
  def configPath = ".pipeline"

  /**
   * Delete temporary files after use.
   */
  def deleteTempFiles = true

  /**
   * Relative path to Helm config file.
   */
  def helmConfig = "helm.yaml"

  /**
   * HTTP proxy to provide to pipeline configurations.
   */
  def httpProxy = null

  /**
   * Absolute path to a Kubernetes config file to specify when executing
   * `helm` or other k8s related commands. By default, none will be specified.
   */
  def kubeConfig = null

  /**
   * Namespace used for Helm/Kubernetes deployments.
   */
  def namespace = "ci"

  /**
   * Docker pull policy when deploying.
   */
  def pullPolicy = "IfNotPresent"

  /**
   * Default Docker registry host used when qualifying public image URLs.
   */
  def registry = "docker-registry.wikimedia.org"

  /**
   * Jenkins credential used to authenticate against the configured registry.
   * Only used when {@link registryPushMethod} is set to "docker-push".
   */
  def registryCredential = null

  /**
   * Alternative Docker registry host used only when registering images.
   */
  def registryInternal = "docker-registry.discovery.wmnet"

  /**
   * Method of pushing remote images to the configured registry. Either
   * "wmf-pusher" to use the "docker-pusher" wrapper script which uses
   * hardcoded and protected credentials or "docker-push" which uses the
   * standard Docker CLI but requires a {@link registryCredential} to be set.
   */
  def registryPushMethod = "wmf-pusher"

  /**
   * Default Docker registry repository used for tagging and registering images.
   */
  def repository = "wikimedia"

  /**
   * A closure used to process all run step commands just prior to execution,
   * typically enforcing the shell interpreter and shell behavior.
   */
  def runWrapper = { cmd -> "#!/bin/bash\nset +o xtrace -o pipefail\n${cmd}" }

  /**
   * Default helm chart registry for helm charts
   */
  def chartRepository = "https://helm-charts.wikimedia.org/stable/"

  /**
   * Timeout for deployment using Helm.
   */
  def timeout = 120

  /**
   * A map of allowed credentials Ids and their binding for this pipeline ([credentialId: credentialbinding])
   */
  def allowedCredentials = [:]

  /**
   * A list of allowed trigger jobs for this pipeline
   */
  def allowedTriggerJobs = []

  /**
   * Jenkins Pipeline Workflow context.
   */
  final def workflowScript

  /**
   * Constructor with Jenkins workflow script context and settings.
   *
   * @param settings Property map.
   * @param workflowScript Jenkins workflow script sandbox object.
   */
  PipelineRunner(Map settings = [:], workflowScript) {
    this.workflowScript = workflowScript

    settings.each { prop, value -> this.@"${prop}" = value }
  }

  /**
   * Assigns to the given image a random name that can be used as a reference
   * for other applications in the same pipeline where an ID is not suitable,
   * such as use as a base image in the build of a subsequent stage.
   *
   * @param imageID ID of image to name.
   */
  String assignLocalName(imageID) {
    def imageName = "localhost/plib-image-${randomAlphanum(8)}"

    workflowScript.sh("docker tag ${arg(imageID)} ${arg(imageName)}")

    imageName
  }

  /**
   * Builds the given image variant and returns an ID for the resulting image.
   *
   * @param variant Image variant name that should be built.
   * @param labels Additional name/value labels to add to the image metadata.
   * @param context Build context given to `docker build`. Default is "."
   * (current directory).
   * @param excludes Files/directories to exclude from build context. This
   * will be used to overwrite any existing .dockerignore prior to the build.
   * @param imagePullPolicy If "always", pass --pull to docker build.
   */
  String build(String variant, Map labels = [:], URI context = URI.create("."), List excludes = null,
               String imagePullPolicy = "always") {
    def cfg = getConfigFile(blubberConfig)

    if (!workflowScript.fileExists(cfg)) {
      throw new FileNotFoundException("failed to build image: no Blubber config found at ${cfg}")
    }

    if (context.scheme && excludes) {
      throw new RuntimeException("excludes may only be used with a local build context")
    }

    def cfgLines = workflowScript.readFile(file: cfg).readLines()
    def dockerfile = cfg

    // Enforce our frontend using a `syntax=` line if need be and write it to
    // a new temporary config. Note that the given config file may already have
    // multiple syntax lines. Docker will use the final one in the first group
    // of commented lines, so let's remove all of those and check whether the
    // last one is allowed or not.
    def headerLines = []

    // Avoid using takeWhile here since it's broken in groovy-cps
    for (def line in cfgLines) {
      if (!line.startsWith('#')) {
        break
      }
      headerLines += line
    }

    def syntaxLines = headerLines.findAll { it =~ '^# *syntax *=' }

    if (syntaxLines.size() == 0 || !hasAllowedFrontend(syntaxLines.last())) {
      dockerfile = getTempFile("blubber.yaml.")
      workflowScript.writeFile(
        file: dockerfile,
        text: "# syntax=${buildkitFrontend}\n" + cfgLines.drop(headerLines.size()).join("\n") + "\n",
      )
    }

    def ignoreFile = ".dockerignore"
    def ignoreFileBackupCreated = false
    def ignoreFileBackup = getTempFile("dockerignore.bak.", true)

    if (excludes) {
      workflowScript.dir(context.path) {
        if (workflowScript.fileExists(ignoreFile)) {
          ignoreFileBackupCreated = true
          workflowScript.sh "cp ${arg(ignoreFile)} ${arg(ignoreFileBackup)}"
        }

        workflowScript.writeFile(text: excludes.join("\n") + "\n", file: ignoreFile)
      }
    }

    try {
      return withTempFile("docker.iid.") { imageIDFile ->
        def labelFlags = labels.collect { k, v -> "--label ${arg(k + "=" + v)}" }.join(" ")

        // Note that buildkit fails when attempting to use a credential store
        // that cannot run headless. Enforce our "environment" credential
        // helper to workaround this issue.
        // See https://github.com/moby/buildkit/issues/1078
        withDocker([ credsStore: "environment" ]) { docker ->
          workflowScript.sh(sprintf(
            'DOCKER_BUILDKIT=1 %s build %s--force-rm=true %s --iidfile %s --file %s --target %s %s',
            docker,
            imagePullPolicy == "always" ? "--pull " : "",
            labelFlags, arg(imageIDFile), arg(dockerfile), arg(variant), arg(context),
          ))
        }

        def imageID = workflowScript.readFile(imageIDFile).trim()
        return imageID.startsWith('sha256:') ? imageID.substring(7) : imageID
      }
    } finally {
      if (ignoreFileBackupCreated) {
        workflowScript.dir(context.path) {
          workflowScript.sh "mv ${arg(ignoreFileBackup)} ${arg(ignoreFile)}"
        }
      }
    }
  }

  /**
   * Copies files from the filesystem of the given stopped container into the
   * working directory.
   *
   * @param container Container ID or name.
   * @param source Source path relative to the container's root (/) directory.
   * @param destination Destination path relative to the working directory.
   * @param archive Archive destination path as a Jenkins artifact. If
   * <code>destination</code> is a directory, it will be archived as a tar.gz
   * file.
   *
   * @return Artifact URL (empty if <code>archive</code> is <code>false</code>)
   */
  String copyFilesFrom(String container, String source, String destination, archive = false) {
    def artifactURL = ""
    destination = sanitizeRelativePath(destination)

    // The logic that docker cp employs when it comes to source directories
    // (whether to copy its contents or the directory itself into the
    // destination) is truly heinous. We're normalizing the behavior to always
    // copy source directory _contents_ by appending a `.`. The source is
    // assumed to be a directory if it is terminated with a `/`.
    if (source.endsWith('/')) {
      source += '.'
    }

    workflowScript.sh(sprintf(
      'mkdir -p "$(dirname %s)" && docker cp %s:%s %s',
      arg(destination), arg(container), arg(source), arg(destination)
    ))

    if (archive) {
      def artifact = destination

      // Tar and compress directories
      if (isDirectory(destination)) {
        artifact += ".tar.gz"
        workflowScript.sh("tar zcf ${arg(artifact)} -C ${arg(destination)} .")
      }

      artifactURL = workflowScript.env.BUILD_URL + "artifact/" + artifact

      workflowScript.archiveArtifacts(
        artifacts: artifact,
        allowEmptyArchive: true,
        followSymlinks: false,
        onlyIfSuccessful: true
      )
    }

    artifactURL
  }

  /**
   * Deploys the given registered image using the configured Helm chart and
   * returns the name of the release.
   *
   * @param imageName Name of the registered image to deploy.
   * @param imageTag  Tag of the registered image to use.
   * @param overrides Additional Helm value overrides to set.
   */
  String deploy(String imageName, String imageTag, Map overrides = [:]) {
    def cfg = workflowScript.readYaml(file: getConfigFile(helmConfig))

    assert cfg instanceof Map && cfg.chart && cfg.chart.name : "you must define 'chart: { name: <helm chart name> }' in ${cfg}"
    cfg.chart.version = cfg.chart.version ?: ""

    deployWithChart(cfg.chart.name, cfg.chart.version, imageName, imageTag, null, overrides)
  }

  /**
   * Deploys the given registered image using the given Helm chart and returns
   * the name of the release.
   *
   * @param chart Chart URL.
   * @param chartVersion the version of the chart.
   * @param imageName Name of the registered image to deploy.
   * @param imageTag  Tag of the registered image to use.
   * @param timeout   Timeout length in seconds
   * @param overrides Additional Helm value overrides to set.
   */
  String deployWithChart(String chart, String chartVersion, String imageName, String imageTag, helmTimeout = timeout, Map overrides = [:]) {
    helmTimeout = helmTimeout ?: timeout
    def values = [
      docker: [
        registry: registry,
        pull_policy: pullPolicy,
      ],
      main_app: [
        image: [repository, imageName].join("/"),
        version: imageTag,
      ]
    ]

    values = merge(values, overrides)

    def valuesFile = getTempFile("values.yaml.")
    def release = randomAlphanum(8)
    def version = chartVersion ? "--version " + arg(chartVersion) : ""

    workflowScript.writeYaml(data: values, file: valuesFile)

    try {
      helm("install ${arg(release)} ${arg(chart)} --namespace=${arg(namespace)} --values ${arg(valuesFile)} " +
        "--debug --wait --timeout ${helmTimeout}s --repo ${chartRepository} ${version}")
    } catch (Exception e) {
      // Attempt to purge failed releases
      purgeRelease(release)
      throw e
    }

    release
  }

  /**
   * Returns a path under configPath to the given config file.
   *
   * @param filePath Relative file path.
   * @param absolute Return the absolute path.
   */
  String getConfigFile(String filePath, boolean absolute = false) {
    def path = [configPath, filePath]

    if (absolute) {
      path.add(0, workflowScript.pwd())
    }

    path.join("/")
  }

  /**
   * Returns a path under configPath to a temp file with the given base name.
   *
   * @param baseName File base name.
   * @param absolute Return the absolute path.
   */
  String getTempFile(String baseName, boolean absolute = false) {
    getConfigFile("${baseName}${randomAlphanum(8)}", absolute)
  }

  /**
   * Tests the given config string and returns whether the BuildKit frontend
   * referenced in the syntax line is allowed to be used over ours. Users are
   * allowed to use a frontend with the same image ref as ours but a different
   * tag/version.
   */
  boolean hasAllowedFrontend(String cfg) {
    def frontendMatch = cfg.readLines().first() =~ '^# *syntax *= *(.+)$'

    if (frontendMatch) {
      def ourRef = parseImageRef(buildkitFrontend)
      def theirRef = parseImageRef(frontendMatch[0][1])

      return ourRef && theirRef && ourRef.name == theirRef.name
    }

    return false
  }

  /**
   * Whether the given path is a directory or not. If no file or directory
   * exists at the given path, false is returned.
   *
   * @param path File/directory path.
   */
  Boolean isDirectory(String path) {
    workflowScript.sh(
      script: "[ -d ${arg(path)} ]",
      returnStatus: true
    ) == 0
  }

  /**
   * Deletes and purges the given Helm release.
   *
   * @param release Previously deployed release name.
   */
  void purgeRelease(String release) {
    purgeReleases([release])
  }

  /**
   * Deletes and purges the given Helm release.
   *
   * @param release Previously deployed release name.
   */
  void purgeReleases(List releases) {
    if (releases.size() > 0) {
      helm("uninstall ${args(releases)} --namespace=${arg(namespace)}")
    }
  }

  /**
   * Name and push an image specified by the given image ID to the WMF Docker
   * registry.
   *
   * By default, the repo name is enforced as "docker-registry.wikimedia.org",
   * and the remote path prefix is enforced as "/wikimedia/". These can be
   * changed by setting {@link registry} and {@link repository}. The method
   * and credential used to push to the registry can be configured by setting
   * {@link registryPushMethod} and {@link registryCredential}.
   *
   * {@code
   * // Pushes built image to docker-registry.wikimedia.org/wikimedia/mathoid:build-123
   * def image = pipeline.build("production")
   * pipeline.registerAs(image, "mathoid", "build-123")
   * }
   * @param imageID Image ID.
   * @param name Remote name to use for the image.
   * @param tag Remote tag to use for the image.
   */
  String registerAs(String imageID, String name, String tag) {
    def pushRegistry = registryPushMethod == "wmf-pusher" ? registryInternal : registry
    def nameAndTag = qualifyRegistryPath(name, pushRegistry) + ":" + tag

    workflowScript.sh("docker tag ${arg(imageID)} ${arg(nameAndTag)}")

    switch (registryPushMethod) {
      case "wmf-pusher":
        workflowScript.sh("sudo /usr/local/bin/docker-pusher ${arg(nameAndTag)}")
        break
      case "docker-push":
        withDocker([ credHelpers: [ (pushRegistry): "environment" ] ]) { docker ->
          workflowScript.withEnv(["DOCKER_CREDENTIAL_HOST=${arg(registry)}"]) {
            workflowScript.withCredentials(
              [[
                $class: 'UsernamePasswordMultiBinding',
                credentialsId: registryCredential,
                passwordVariable: 'DOCKER_CREDENTIAL_USERNAME',
                usernameVariable: 'DOCKER_CREDENTIAL_PASSWORD'
              ]]
            ) {
              workflowScript.sh("${docker} push ${arg(nameAndTag)}")
            }
          }
        }
        break
      default:
        throw new RuntimeException("unknown registry push method '${registryPushMethod}'")
    }

    nameAndTag
  }

  void updateChart(chart, version, environments) {
    def environmentsString = environments.collect { "-e " + arg(it) }.join(" ")

    workflowScript.sh("./update_version/update_version.py -s ${arg(chart)} -v ${arg(version)} ${environmentsString}")
  }

  void commitAndPush(Map options = [:]) {
    def topicString = ""
    def commitMessages = ""
    def reviewersString = ""

    if (options.topic) {
      topicString = '%topic=' + options.topic
    }

    if (options.commitMessages) {
      commitMessages = options.commitMessages
    }

    if (options.reviewers) {
      options.reviewers.each {
        reviewersString += ",r=${it}"
      }
    }

    workflowScript.sh("""\
        |git add -A
        |git config user.email tcipriani+pipelinebot@wikimedia.org
        |git config user.name PipelineBot
        |git commit ${commitMessages}
      |""".stripMargin())

    try {
      workflowScript.withCredentials(
        [[
          $class: 'UsernamePasswordMultiBinding',
          credentialsId: 'gerrit.pipelinebot',
          passwordVariable: 'GIT_PASSWORD',
          usernameVariable: 'GIT_USERNAME'
        ]]
      ) { workflowScript.sh(
        sprintf('''\
          |set +x
          |git config credential.username ${GIT_USERNAME}
          |git config credential.helper '!echo password=\${GIT_PASSWORD} #'
          |set -x
          |git push origin %s
        |'''.stripMargin(), arg("HEAD:refs/for/master" + topicString + reviewersString))
      )}
    } catch (Exception e) {
      workflowScript.sh("Error: ${e}")
      throw e
    } finally {
      workflowScript.sh("""\
        |set +e
        |git config --unset credential.helper
        |git config --unset credential.username
        |set -e
      |""".stripMargin())
    }
  }

  void updateCharts(List promote, List reviewers) {
    def buildMessage = "Job: ${workflowScript.env.JOB_NAME} " +
      "Build: ${workflowScript.env.BUILD_NUMBER}"

    promote.each {
      def chart = it.chart
      def version = it.version
      def environments = it.environments
      def branchName = randomAlphanum(8)
      def promoteSubject = "${chart}: pipeline bot promote"
      def promoteMessage = "Promote ${chart} to version ${version}"
      def commitMessages = "-m ${arg(promoteSubject)} -m ${arg(promoteMessage)} -m ${arg(buildMessage)}"

      workflowScript.sh("git checkout -b ${arg(branchName)}")
      updateChart(chart, version, environments)
      commitAndPush([
        commitMessages: commitMessages,
        topic: 'pipeline-promote',
        reviewers: reviewers
      ])
      workflowScript.sh("git checkout master")
    }
  }

  /**
   * Removes the given containers, forcefully stopping them if they're still
   * running.
   *
   * @param containers List of container IDs/names.
   */
  void removeContainers(List containers) {
    if (containers.size() > 0) {
      workflowScript.sh("docker rm --force ${args(containers)}")
    }
  }

  /**
   * Removes the given image from the local cache. All tags are removed from
   * the image as well.
   *
   * @param imageID ID of the image to remove.
   */
  void removeImage(String imageID) {
    removeImages([imageID])
  }

  /**
   * Removes the given images from the local cache.
   *
   * @param imageIDs IDs of images to remove.
   */
  void removeImages(List imageIDs) {
    if (imageIDs.size() > 0) {
      workflowScript.sh("docker rmi --force ${args(imageIDs)}")
    }
  }

  /**
   * Submits a comment to Gerrit with the build result and links to published
   * images.
   *
   * @param imageName Fully qualified name of published image.
   * @param imageTags Image tags.
   */
  void reportImageToGerrit(imageName, imageTags = []) {
    def comment

    if (workflowScript.currentBuild.result == 'SUCCESS' && imageName) {
      comment = new GerritPipelineComment(
        jobName: workflowScript.env.JOB_NAME,
        buildNumber: workflowScript.env.BUILD_NUMBER,
        jobStatus: workflowScript.currentBuild.result,
        image: imageName,
        tags: imageTags,
      )
    } else {
      comment = new GerritPipelineComment(
        jobName: workflowScript.env.JOB_NAME,
        buildNumber: workflowScript.env.BUILD_NUMBER,
        jobStatus: workflowScript.currentBuild.result,
      )
    }

    GerritReview.post(workflowScript, comment)
  }

  /* This is like run() below, but it accepts its arguments as a map to make it
   * easier to omit optional arguments.
   */
  RunResult run(Map args) {
    if (args.imageID == null) {
      throw new RuntimeException("run: imageID must be supplied")
    }

    return run(args.imageID,
               args.arguments ?: [],
               args.envVars ?: [:],
               args.creds ?: [],
               args.outputLines ?: 0,
               args.removeContainer ?: false)
  }

  /**
   * Runs a container using the image specified by the given ID.
   *
   * @param imageID Image ID.
   * @param arguments Entry-point arguments.
   * @param envVars Environment variables to set.
   * @param creds Credentials to expose to the running container process.
   * @param outputLines Return the last n lines of the container's output.
   * @param removeContainer If true, remove the container after the process exists
   *
   * @return RunResult Last <code>outputLines</code> of the container's output
   * and container name.
   */
  RunResult run(
    String imageID,
    List arguments = [],
    Map envVars = [:],
    List creds = [],
    Integer outputLines = 0,
    Boolean removeContainer = false) {

    def credBindings = []
    def credsWithVars = [:]
    def credentialManager = new PipelineCredentialManager([allowedCredentials: allowedCredentials])

    creds.each {
      def cred = credentialManager.generateCredential(it)
      credBindings += cred.getBinding()
      credsWithVars += cred.getDockerEnvVars()
    }

    def argsString = args([imageID] + arguments)
    def containerName = "plib-run-${randomAlphanum(8)}"
    def runCmd = sprintf(
      'docker run --name %s --rm=%s %ssha256:%s',
            arg(containerName),
            removeContainer,
            envs(envVars + credsWithVars),
            argsString
    )

    workflowScript.echo(runCmd)

    workflowScript.timeout(time: 20, unit: "MINUTES") {
      workflowScript.withCredentials(credBindings) {
        try {
          return new RunResult(
            container: containerName,
            output: withOutput(runCmd, outputLines) { cmd ->
              workflowScript.sh(runWrapper(cmd))
            },
            containerRemoved: removeContainer,
          )
        } catch (Exception ex) {
          // T290608
          // ensure container removal upon failed command, abort, timeout, etc.
          workflowScript.sh("docker rm -f ${arg(containerName)} || true")
          throw ex
        }
      }
    }
  }

  /**
   * Trigger a downstream job on the local Jenkins server
   *
   * @param options for the build step
   */
  void triggerJob(Map options) {
    if (allowedTriggerJobs.contains(options.job)) {
      // Xref: https://www.jenkins.io/doc/pipeline/steps/pipeline-build-step/
      workflowScript.build(options)
    } else {
      throw new RuntimeException("Invalid trigger job '${options.job}'. Allowed trigger jobs: ${allowedTriggerJobs.inspect()}")
    }
  }

  /**
   * Fully qualifies an image name to a public registry path.
   *
   * @param name Image name.
   * @param registryName Alternative registry. Defaults to {@link registry}.
   */
  String qualifyRegistryPath(String name, String registryName = "") {
    assert !name.contains("/") : "image name ${name} cannot contain slashes"
    assert name.toLowerCase() == name : "image name ${name} must be all lower case"

    [registryName ?: registry, repository, name].join("/")
  }

  /**
   * Runs end-to-end tests for the given release via `helm test`.
   *
   * @param release Previously deployed release name.
   */
  void testRelease(String release) {
    helm("test ${arg(release)}")
  }

  /**
   * Temporarily overrides the blubberConfig with the given config which can
   * either be a path to a new config file or an object containing valid
   * inline Blubber configuration.
   */
  def withBlubberConfig(bc, Closure c) {
    if (bc instanceof String || bc instanceof GString) {
      def prevConfig = blubberConfig

      try {
        blubberConfig = bc
        return c()
      } finally {
        blubberConfig = prevConfig
      }
    }

    // Config is a non-String object. Write it to a temp file and recurse.
    // Note that the configPath prefix is removed from the temp file path
    // since blubberConfig is expected to be relative to that directory
    withTempFile("blubber.yaml.") { path ->
      workflowScript.writeYaml(file: path, data: bc)
      withBlubberConfig(path - "${configPath}/", c)
    }
  }

  /**
   * Evaluates the given closure, passing it a docker command that uses the
   * given Docker configuration.
   */
  void withDocker(Map config, Closure c) {
    withTempDirectory() { tempDir ->
      workflowScript.writeJSON(
        file: [ tempDir, "config.json" ].join("/"),
        json: config,
      )
      c("docker --config ${arg(tempDir)}")
    }
  }

  /**
   * Rewrites the given command with a stdout redirect to `tee` to both
   * capture its output to a file and maintain its output to the console. The
   * rewritten command is passed to the given closure for execution and the
   * output-file contents are returned following deletion of the temporary
   * file.
   */
  String withOutput(String cmd, Integer lines = -1, Closure c) {
    if (lines == 0) {
      c(cmd)
      return ""
    }

    withTempFile("output.") { outputFile ->
      c("${cmd} | tee ${arg(outputFile)}")
      def contents = workflowScript.readFile(outputFile)

      if (lines < 0) {
        return contents
      }

      return contents.readLines().takeRight(lines).join("\n") + "\n"
    }
  }

  /**
   * Generates a path to a new temporary file under the pipeline directory,
   * calls the given closure using the new path, and cleans up by deleting the
   * temporary file.
   */
  def withTempFile(String prefix, Closure c) {
    def path = getTempFile(prefix)

    try {
      return c(path)
    } finally {
      if (deleteTempFiles) {
        workflowScript.sh("rm -f ${arg(path)}")
      }
    }
  }

  /**
   * Creates a temporary directory, calls the given closure with the directory
   * as an argument, and ensures the directory is removed.
   */
  void withTempDirectory(Closure c) {
    def tempDir = workflowScript.sh(returnStdout: true, script: "mktemp -d").trim()

    try {
      c(tempDir)
    } finally {
      if (deleteTempFiles) {
        workflowScript.dir(tempDir) {
          workflowScript.deleteDir()
        }
      }
    }
  }

  private

  /**
   * Execute a helm command
   */
  void helm(String cmd) {
    kubeCmd("helm3 ${cmd}")
  }

  /**
   * Execute a Kubernetes related command, specifying the configured
   * kubeConfig.
   */
  void kubeCmd(String cmd) {
    def env = kubeConfig ? "KUBECONFIG=${arg(kubeConfig)} " : ""
    workflowScript.sh("${env}${cmd}")
  }

  /**
   * State of a run container returned by {@link run}.
   */
  class RunResult implements Serializable {
    /**
     * Container name.
     */
    String container

    /**
     * Output of the container entrypoint.
     */
    String output

    /**
     * Boolean indicating whether or not the container has been removed already.
     */
    Boolean containerRemoved
  }
}
