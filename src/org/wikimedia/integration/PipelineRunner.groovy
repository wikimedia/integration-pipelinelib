package org.wikimedia.integration

import java.io.FileNotFoundException

import static org.wikimedia.integration.Utility.*

import org.wikimedia.integration.GerritPipelineComment
import org.wikimedia.integration.GerritReview

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
   * Alternative Docker registry host used only when registering images.
   */
  def registryInternal = "docker-registry.discovery.wmnet"

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
   */
  String build(String variant, Map labels = [:]) {
    def cfg = getConfigFile(blubberConfig)

    if (!workflowScript.fileExists(cfg)) {
      throw new FileNotFoundException("failed to build image: no Blubber config found at ${cfg}")
    }

    def blubber = new Blubber(workflowScript, cfg, blubberoidURL)
    def dockerfile = getTempFile("Dockerfile.")

    workflowScript.writeFile(text: blubber.generateDockerfile(variant), file: dockerfile)

    def labelFlags = labels.collect { k, v -> "--label ${arg(k + "=" + v)}" }.join(" ")
    def dockerBuild = "docker build --pull --force-rm=true ${labelFlags} --file ${arg(dockerfile)} ."

    def output = workflowScript.sh(returnStdout: true, script: dockerBuild)

    // Return just the image ID from `docker build` output
    output.substring(output.lastIndexOf(" ") + 1).trim()
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

    deployWithChart(cfg.chart.name, cfg.chart.version, imageName, imageTag, overrides)
  }

  /**
   * Deploys the given registered image using the given Helm chart and returns
   * the name of the release.
   *
   * @param chart Chart URL.
   * @param chartVersion the version of the chart.
   * @param imageName Name of the registered image to deploy.
   * @param imageTag  Tag of the registered image to use.
   * @param overrides Additional Helm value overrides to set.
   */
  String deployWithChart(String chart, String chartVersion, String imageName, String imageTag, Map overrides = [:]) {
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
    def release = imageName + "-" + randomAlphanum(8)
    def version = chartVersion ? "--version " + arg(chartVersion) : ""

    workflowScript.writeYaml(data: values, file: valuesFile)

    try {
      helm("install ${arg(chart)} --namespace=${arg(namespace)} --values ${arg(valuesFile)} " +
        "-n ${arg(release)} --debug --wait --timeout ${timeout} --repo ${chartRepository} ${version}")
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
   * The repo name is enforced as "docker-registry.wikimedia.org", and the
   * remote path prefix is enforced as "/wikimedia/".
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
    def nameAndTag = qualifyRegistryPath(name, registryInternal) + ":" + tag

    workflowScript.sh("docker tag ${arg(imageID)} ${arg(nameAndTag)} && " +
                      "sudo /usr/local/bin/docker-pusher ${arg(nameAndTag)}")

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
   */
  void run(String imageID, List arguments = [], Map envVars = [:], Map creds = [:]) {
    def textCredsList = creds.collect{ k, v -> [$class: 'StringBinding', credentialsId: k, variable: v]}
    def argsString = args([imageID] + arguments)
    def credsWithVars = creds.collectEntries { k, v -> [v, '\${' + v + '}'] }
    def runString = sprintf('exec docker run --rm %ssha256:%s', envs(envVars + credsWithVars), argsString)

    workflowScript.echo(runString)

    workflowScript.timeout(time: 20, unit: "MINUTES") {
      workflowScript.withCredentials(textCredsList){
        workflowScript.sh ( sprintf('''
          set +x
          %s
          set -x
        ''', runString ) )
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
