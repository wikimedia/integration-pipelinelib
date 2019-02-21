package org.wikimedia.integration

import java.net.URLEncoder
import groovy.json.JsonOutput

/**
 * Gerrit review that can be used to comment on a gerrit patchset
 *
 * {@code
 * import org.wikimedia.integration.GerritReview
 * import org.wikimedia.integration.GerritComment
 *
 * stage('comment') {
 *   comment = new GerritComment(
 *     jobName: xx,
 *     buildNumber: xx,
 *     jobStatus: xx,
 *     image: xx,
 *     tags: xx
 *   )
 *   GerritReview.post(this, comment)
 * }
 * }
 */

class GerritReview implements Serializable {
  /**
   * Jenkins pipeline workflow script context.
   */
  final def workflowScript

  /**
   * Gerrit URL
   */
  final def gerritURL = 'https://gerrit.wikimedia.org/r'

  /**
   * Name of the auth credentials in jenkins to use in gerrit
   */
  final def gerritAuthCreds = 'gerrit.pipelinebot'

  /**
   * GerritComment with information for message body
   */
  final GerritComment comment

  GerritReview(workflowScript, GerritComment comment) {
    this.workflowScript = workflowScript
    this.comment = comment
  }

  /**
   * URLEncoded ZUUL_PROJECT from the environment.
   *
   * This should be set for all patchsets.
   */
  String getProject() {
    URLEncoder.encode(this.workflowScript.env.ZUUL_PROJECT, 'UTF-8')
  }

  /**
   * Return a full authorized url for a gerrit revision review.
   *
   * May return an empty string in the cases of an unexpected environment.
   */
  String getRequestURL() {
    def change = this.workflowScript.env.ZUUL_CHANGE
    def revision = this.workflowScript.env.ZUUL_PATCHSET

    if (! revision || ! change) { return "" }

    def changeId = [this.getProject(), change].join('~')


    [
      this.gerritURL,
      'a/changes',
      changeId,
      'revisions',
      revision,
      'review',
    ].join('/')
  }

  /**
   * Format gerritcomment as a json message.
   */
  String getBody() {
    JsonOutput.toJson([message: this.comment.formatMessage()])
  }

 /**
  * Static method to POST GerritComment to a particular change.
  *
  * Uses the Gerrit RESTAPI to POST a comment on a patchset revision.
  *
  * @param workflowScript Jenkins workflow script context.
  * @param comment GerritComment
  */
  static String post(workflowScript, GerritComment comment) {
    def gr = new GerritReview(workflowScript, comment)
    def url = gr.getRequestURL()

    if (! url) {
      gr.workflowScript.error "Could not determine Gerrit ChangeID from Environment. Aborting."
    }

    def response = gr.workflowScript.httpRequest(
      url: url,
      httpMode: 'POST',
      customHeaders: [[name: "content-type", value: 'application/json']],
      requestBody: gr.getBody(),
      consoleLogResponseBody: true,
      validResponseCodes: "200",
      authentication: gr.gerritAuthCreds
    )
    response.content
  }
}
