package org.wikimedia.integration

import java.io.FileNotFoundException

import static org.wikimedia.integration.Utility.*

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
   * Default Docker registry host used for tagging and registering images.
   */
  def registry = "docker-registry.wikimedia.org"

  /**
   * Default Docker registry repository used for tagging and registering images.
   */
  def repository = "wikimedia"

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

    def blubber = new Blubber(workflowScript, cfg)

    blubber.build(variant, labels)
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

    assert cfg instanceof Map && cfg.chart : "you must define 'chart: <helm chart url>' in ${cfg}"

    def values = [
      "docker.registry": registry,
      "docker.pull_policy": pullPolicy,
      "main_app.image": [repository, imageName].join("/"),
      "main_app.version": imageTag,
    ] + overrides

    values = values.collect { k, v -> arg(k + "=" + v) }.join(",")

    def release = imageName + "-" + randomAlphanum(8)

    helm("install --namespace=${arg(namespace)} --set ${values} -n ${arg(release)} " +
         "--debug --wait --timeout ${timeout} ${arg(cfg.chart)}")

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
   * Deletes and purges the given Helm release.
   *
   * @param release Previously deployed release name.
   */
  void purgeRelease(String release) {
    helm("delete --purge ${arg(release)}")
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
    def nameAndTag = qualifyRegistryPath(name) + ":" + tag

    workflowScript.sh("docker tag ${arg(imageID)} ${arg(nameAndTag)} && " +
                      "sudo /usr/local/bin/docker-pusher ${arg(nameAndTag)}")

    nameAndTag
  }

  /**
   * Removes the given image from the local cache. All tags are removed from
   * the image as well.
   *
   * @param imageID ID of the image to remove.
   */
  void removeImage(String imageID) {
    workflowScript.sh("docker rmi --force ${arg(imageID)}")
  }

  /**
   * Runs a container using the image specified by the given ID.
   *
   * @param imageID Image ID.
   */
  void run(String imageID) {
    workflowScript.timeout(time: 20, unit: "MINUTES") {
      workflowScript.sh("exec docker run --rm ${arg(imageID)}")
    }
  }

  /**
   * Fully qualifies an image name to a registry path.
   *
   * @param name Image name.
   */
  String qualifyRegistryPath(String name) {
    assert !name.contains("/") : "image name ${name} cannot contain slashes"

    [registry, repository, name].join("/")
  }

  /**
   * Runs end-to-end tests for the given release via `helm test`.
   *
   * @param release Previously deployed release name.
   */
  void testRelease(String release) {
    helm("test --cleanup ${arg(release)}")
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
