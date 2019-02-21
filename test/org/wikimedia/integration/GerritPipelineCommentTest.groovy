import groovy.util.GroovyTestCase

import org.wikimedia.integration.GerritPipelineComment

class GerritCommentTestCase extends GroovyTestCase {
  private GerritPipelineComment gerritComment

  void testGetDashboardOutput() {
    gerritComment = new GerritPipelineComment(
      jobName: "service-pipeline-test-and-publish"
    )
    assert gerritComment.formatDashboard() == 'pipeline-dashboard: service-pipeline-test-and-publish'
  }

  void testGetResultOutput() {
    gerritComment = new GerritPipelineComment(
      jobName: 'service-pipeline-test-and-publish',
      jobStatus: 'SUCCESS',
      buildNumber: '25'
    )
    assert gerritComment.formatResult() == \
      'pipeline-build-result: SUCCESS (job: service-pipeline-test-and-publish, build: 25)'
  }

  void testGetFormatImage() {
    def imageName = 'docker-registry.wikimedia.org/wikimedia/mediawiki-services-citoid'
    def expected = "IMAGE:\n ${imageName}"
    gerritComment = new GerritPipelineComment(image: imageName)
    assert gerritComment.formatImage() == expected
  }

  void testGetFormatTags() {
    def tags = ['2019-02-11-214153-production', 'fc52e49b051872b282c6a66be6649c7d437bf066']
    def expected = "TAGS:\n 2019-02-11-214153-production, fc52e49b051872b282c6a66be6649c7d437bf066"
    gerritComment = new GerritPipelineComment(tags: tags)
    assert gerritComment.formatTags() == expected
  }

  void testwithoutImage() {
    def expected = '''\
    pipeline-dashboard: service-pipeline-test-and-publish
    pipeline-build-result: SUCCESS (job: service-pipeline-test-and-publish, build: 25)
    '''.stripIndent()

    gerritComment = new GerritPipelineComment(
      jobName: 'service-pipeline-test-and-publish',
      jobStatus: 'SUCCESS',
      buildNumber: '25',
    )

    assert gerritComment.formatMessage() == expected
  }

  void testwithImage() {
    def tags = ['2019-02-11-214153-production', 'fc52e49b051872b282c6a66be6649c7d437bf066']
    def imageName = 'docker-registry.wikimedia.org/wikimedia/mediawiki-services-citoid'

    def expected = '''\
    pipeline-dashboard: service-pipeline-test-and-publish
    pipeline-build-result: SUCCESS (job: service-pipeline-test-and-publish, build: 25)

    IMAGE:
     docker-registry.wikimedia.org/wikimedia/mediawiki-services-citoid

    TAGS:
     2019-02-11-214153-production, fc52e49b051872b282c6a66be6649c7d437bf066
    '''.stripIndent()

    gerritComment = new GerritPipelineComment(
      jobName: 'service-pipeline-test-and-publish',
      jobStatus: 'SUCCESS',
      buildNumber: '25',
      image: imageName,
      tags: tags
    )

    assert gerritComment.formatMessage() == expected
  }
}
