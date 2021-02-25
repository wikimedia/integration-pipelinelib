package org.wikimedia.integration

import java.net.URLEncoder

import static org.wikimedia.integration.Utility.arg

/**
 * Provides an interface to Blubber for generating Dockerfiles.
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
   * Blubberoid base service URL.
   */
  final String blubberoidURL

  /**
   * Blubber constructor.
   *
   * @param workflowScript Jenkins workflow script context.
   * @param configPath Blubber config path.
   * @param blubberoidURL Blubberoid service URL.
   */
  Blubber(workflowScript, String configPath, String blubberoidURL) {
    this.workflowScript = workflowScript
    this.configPath = configPath
    this.blubberoidURL = blubberoidURL
  }

  /**
   * Returns a valid Dockerfile for the given variant.
   *
   * @param variant Blubber variant name.
   */
  String generateDockerfile(String variant) {
    def config = workflowScript.readFile(file: configPath)
    def headers = [[name: "content-type", value: getConfigMediaType()]]
    def response = workflowScript.httpRequest(url: getRequestURL(variant),
                                              httpMode: "POST",
                                              customHeaders: headers,
                                              requestBody: config,
                                              consoleLogResponseBody: true,
                                              validResponseCodes: "200")

    response.content
  }

  /**
   * Returns a request media type based on the config file extensions.
   */
  String getConfigMediaType() {
    def basename = configPath.substring(configPath.lastIndexOf("/") + 1)
    def exts = basename.tokenize(".").drop(1)

    if (["yaml", "yml"].intersect(exts)) {
      return "application/yaml"
    }

    return "application/json"
  }

  /**
   * Return a request URL for the given variant.
   */
  String getRequestURL(String variant) {
    blubberoidURL + URLEncoder.encode(variant, "UTF-8")
  }
}
