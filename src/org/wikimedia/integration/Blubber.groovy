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
   * Jenkins pipeline steps context.
   */
  final def steps

  /**
   * Blubber constructor.
   *
   * @param steps Jenkins pipeline steps context.
   * @param configPath Blubber config path.
   */
  Blubber(steps, String configPath) {
    this.steps = steps
    this.configPath = configPath
  }

  /**
   * Builds the given variant and tags the image with the given tag name and
   * labels.
   *
   * @param variant Blubber variant name that should be built.
   * @param tag Tag to use for built image.
   * @param labels Additional "name=value" labels to add to the image.
   */
  void build(String variant, String tag, List<String> labels) {
    def labelFlags = labels.collect { "--label ${arg(it)}" }.join(" ")

    steps.sh "blubber ${arg(this.configPath)} ${arg(variant)} | " +
             "docker build --pull --tag ${arg(tag)} ${labelFlags} --file - ."
  }
}
