import groovy.util.GroovyTestCase

import org.wikimedia.integration.GerritReview
import org.wikimedia.integration.GerritPipelineComment

class GerritReviewTestCase extends GroovyTestCase {
  private class Env {
    String ZUUL_PATCHSET = '8'
    String ZUUL_CHANGE = '486851'
    String ZUUL_PROJECT = 'mediawiki/services/citoid'
  }

  private class WorkflowScript {
    Env env
    WorkflowScript(env) { this.env = env }
  }

  void testReviewURL() {
    def gr = new GerritReview(new WorkflowScript(new Env()), new GerritPipelineComment())
    assert gr.getProject() == 'mediawiki%2Fservices%2Fcitoid'
    assert gr.getRequestURL() == 'https://gerrit.wikimedia.org/r/a/changes/mediawiki%2Fservices%2Fcitoid~486851/revisions/8/review'
  }

  void testReviewBody() {
    def expected = '{"message":"pipeline-dashboard: service-pipeline-test-and-publish\\n'
    expected += 'pipeline-build-result: SUCCESS '
    expected += '(job: service-pipeline-test-and-publish, build: 25)\\n"}'

    def gerritComment = new GerritPipelineComment(
      jobName: 'service-pipeline-test-and-publish',
      jobStatus: 'SUCCESS',
      buildNumber: '25',
    )

    def gr = new GerritReview(new WorkflowScript(new Env()), gerritComment)
    assert gr.getBody() == expected
  }
}
