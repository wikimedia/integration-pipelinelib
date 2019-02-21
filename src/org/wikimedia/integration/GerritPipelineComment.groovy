package org.wikimedia.integration

/**
 * Gerrit revision that can be used to comment on a gerrit patchset
 *
 * {@code
 * import org.wikimedia.integration.GerritReview
 * import org.wikimedia.integration.GerritPipelineComment
 *
 * stage('comment') {
 *   comment = new GerritPipelineComment(
 *     jobName: xx,
 *     buildNumber: xx,
 *     jobStatus: xx,
 *     image: xx,
 *     tags: xx
 *   )
 *   GerritReview.post(this, comment)
 * }
 */
class GerritPipelineComment extends GerritComment implements Serializable {
  /**
   * Name of the job
   */
  String jobName

  /**
   * Jenkins build number
   */
  String buildNumber

  /**
   * Image in the docker registry
   */
  String image

  /**
   * Build status
   */
  String jobStatus

  /**
   * Image tags
   */
  List<String> tags

  String formatDashboard() {
    "pipeline-dashboard: ${this.jobName}"
  }

  String formatResult() {
    "pipeline-build-result: ${this.jobStatus} (job: ${this.jobName}, build: ${this.buildNumber})"
  }

  String formatImage() {
    "IMAGE:\n ${this.image}"
  }

  String formatTags() {
    "TAGS:\n ${this.tags.join(', ')}"
  }

  /**
   * Format final message output
   */
  String formatMessage() {
    def msg = "${this.formatDashboard()}\n${this.formatResult()}\n"

    if (this.image != null) {
      msg = "${msg}\n${this.formatImage()}\n"
    }
    if (this.tags != null) {
      msg = "${msg}\n${this.formatTags()}\n"
    }

    msg
  }

  GerritPipelineComment(Map settings = [:]) {
    settings.each { prop, value -> this.@"${prop}" = value }
  }
}
