package org.wikimedia.integration

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
   * Base URL for the Blubberoid service.
   */
  def blubberoidURL = "https://blubberoid.wikimedia.org/v1/"

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
   * Builds the given image variant and returns an ID for the resulting image.
   *
   * @param variant Image variant name that should be built.
   * @param labels Additional name/value labels to add to the image metadata.
   * @param context Build context given to `docker build`. Default is "."
   * (current directory).
   * @param excludes Files/directories to exclude from build context. This
   * will be used to overwrite any existing .dockerignore prior to the build.
   */
  String build(String variant, Map labels = [:], URI context = URI.create("."), List excludes = null) {
    def cfg = getConfigFile(blubberConfig)

    if (!workflowScript.fileExists(cfg)) {
      throw new FileNotFoundException("failed to build image: no Blubber config found at ${cfg}")
    }

    if (context.scheme && excludes) {
      throw new RuntimeException("excludes may only be used with a local build context")
    }

    def blubber = new Blubber(workflowScript, cfg, blubberoidURL)
    def dockerfile = getTempFile("Dockerfile.")

    workflowScript.writeFile(text: blubber.generateDockerfile(variant), file: dockerfile)

    def ignoreFile = ".dockerignore"
    def ignoreFileBackup

    if (excludes) {
      workflowScript.dir(context.path) {
        if (workflowScript.fileExists(ignoreFile)) {
          ignoreFileBackup = getTempFile("dockerignore.bak.")
          workflowScript.sh "cp ${arg(ignoreFile)} ${arg(ignoreFileBackup)}"
        }

        workflowScript.writeFile(text: excludes.join("\n") + "\n", file: ignoreFile)
      }
    }

    try {
      return withTempFile("docker.iid.") { imageIDFile ->
        def labelFlags = labels.collect { k, v -> "--label ${arg(k + "=" + v)}" }.join(" ")

        workflowScript.sh(sprintf(
          'docker build --pull --force-rm=true %s --iidfile %s --file %s %s',
          labelFlags, arg(imageIDFile), arg(dockerfile), arg(context)
        ))

        def imageID = workflowScript.readFile(imageIDFile).trim()
        return imageID.startsWith('sha256:') ? imageID.substring(7) : imageID
      }
    } finally {
      if (ignoreFileBackup) {
        workflowScript.dir(context.path) {
          workflowScript.sh "mv ${arg(ignoreFileBackup)} ${arg(ignoreFile)}"
        }
      }
    }
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
      helm("install ${arg(chart)} --namespace=${arg(namespace)} --values ${arg(valuesFile)} " +
        "-n ${arg(release)} --debug --wait --timeout ${helmTimeout} --repo ${chartRepository} ${version}")
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
   */
  String getConfigFile(String filePath) {
    [configPath, filePath].join("/")
  }

  /**
   * Returns a path under configPath to a temp file with the given base name.
   *
   * @param baseName File base name.
   */
  String getTempFile(String baseName) {
    getConfigFile("${baseName}${randomAlphanum(8)}")
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
      helm("delete --purge ${args(releases)}")
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
        withTempDirectory() { tempDir ->
          workflowScript.writeJSON(
            file: [tempDir, "config.json"].join("/"),
            json: [ credHelpers: [ (pushRegistry): "environment" ] ])

          workflowScript.withEnv(["DOCKER_CREDENTIAL_HOST=${arg(registry)}"]) {
            workflowScript.withCredentials(
              [[
                $class: 'UsernamePasswordMultiBinding',
                credentialsId: registryCredential,
                passwordVariable: 'DOCKER_CREDENTIAL_USERNAME',
                usernameVariable: 'DOCKER_CREDENTIAL_PASSWORD'
              ]]
            ) {
              workflowScript.sh("docker --config ${arg(tempDir)} push ${arg(nameAndTag)}")
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

    if (options.topic) {
      topicString = '%topic=' + options.topic
    }

    if (options.commitMessages) {
      commitMessages = options.commitMessages
    }

    workflowScript.sh("""\
        |git add -A
        |git config user.email tcipriani+pipelinebot@wikimedia.org
        |git config user.name PipelineBot
        |git commit ${commitMessages}
      """.stripMargin())
    
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
          |git push origin HEAD:refs/for/master%s
        |'''.stripMargin(), topicString)
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
      """.stripMargin())
    }
  }

  void updateCharts(List promote) {
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
        topic: 'pipeline-promote'
      ])
      workflowScript.sh("git checkout master")
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

  /**
   * Runs a container using the image specified by the given ID.
   *
   * @param imageID Image ID.
   * @param arguments Entry-point arguments.
   * @param envVars Environment variables to set.
   * @param creds Credentials to expose to the running container process.
   * @param outputLines Return the last n lines of the container's output.
   *
   * @return String Last <code>outputLines</code> of the container's output.
   */
  String run(String imageID, List arguments = [], Map envVars = [:], List creds = [],
    Integer outputLines = 0) {

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
      'docker run --rm --name %s %ssha256:%s',
      arg(containerName), envs(envVars + credsWithVars), argsString
    )

    workflowScript.echo(runCmd)

    workflowScript.timeout(time: 20, unit: "MINUTES") {
      workflowScript.withCredentials(credBindings) {
        try {
          return withOutput(runCmd, outputLines) { cmd ->
            workflowScript.sh("set +x\n${cmd}")
          }
        } catch (InterruptedException ex) {
          // ensure container termination upon abort/timeout/etc.
          workflowScript.sh("docker stop ${arg(containerName)}")
          throw ex
        }
      }
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
    helm("test --logs --cleanup ${arg(release)}")
  }

  /**
   * Temporarily overrides the blubberConfig with the given config which can
   * either be a path to a new config file or an object containing valid
   * inline Blubber configuration.
   */
  def withBlubberConfig(bc, Closure c) {
    if (!bc) {
      return c()
    }

    if (bc instanceof String) {
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
    def tempDir = workflowScript.sh(returnStdout: true, script: "mktemp -d")

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
   * Execute a helm command, specifying the right tiller namespace.
   */
  void helm(String cmd) {
    kubeCmd("helm --tiller-namespace=${arg(namespace)} ${cmd}")
  }

  /**
   * Execute a Kubernetes related command, specifying the configured
   * kubeConfig.
   */
  void kubeCmd(String cmd) {
    def env = kubeConfig ? "KUBECONFIG=${arg(kubeConfig)} " : ""
    workflowScript.sh("${env}${cmd}")
  }
}
