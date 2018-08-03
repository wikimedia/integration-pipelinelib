package org.wikimedia.integration

import static org.wikimedia.integration.Utility.arg

/**
 * Provides an interface to Blubber for building container images.
 */
class Blubber implements Serializable {
  /**
   * Blubber config path.
   */
  final String configPath

  /**
   * Jenkins pipeline workflow script context.
   */
  final def workflowScript

  /**
   * Blubber constructor.
   *
   * @param workflowScript Jenkins workflow script context.
   * @param configPath Blubber config path.
   */
  Blubber(workflowScript, String configPath) {
    this.workflowScript = workflowScript
    this.configPath = configPath
  }

  /**
   * Builds the given variant and tags the image with the given tag name and
   * labels.
   *
   * @param variant Blubber variant name that should be built.
   * @param labels Additional name/value labels to add to the image.
   */
  String build(String variant, Map labels = [:]) {
    def labelFlags = labels.collect { k, v -> "--label ${arg(k + "=" + v)}" }.join(" ")

    def cmd = "blubber ${arg(configPath)} ${arg(variant)} | " +
              "docker build --pull ${labelFlags} --file - ."

    def output = workflowScript.sh(returnStdout: true, script: cmd)

    // Return just the image ID from `docker build` output
    output.substring(output.lastIndexOf(" ") + 1).trim()
  }
}
