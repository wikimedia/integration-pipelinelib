def plib = library(identifier: 'pipelinelib@FETCH_HEAD', retriever: legacySCM(scm)).org.wikimedia.integration

node('blubber') {
  def blubberoidURL = "https://blubberoid.wikimedia.org/v1/"

  stage('Test checkout') {
    def patchset = plib.PatchSet.fromZuul(params)
    checkout(patchset.getSCM())
  }
}
