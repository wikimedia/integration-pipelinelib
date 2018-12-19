/**
 * Functionally tests pipelinelib by retrieving the patchset referenced by
 * `scm` and importing the library into the current context before making some
 * basic assertions about its methods behaviors. Note that the Jenkins job
 * that runs this Jenkinsfile must already define `scm` with the correct Zuul
 * parameters.
 */
def plib = library(identifier: 'pipelinelib@FETCH_HEAD', retriever: legacySCM(scm)).org.wikimedia.integration
def prunner = plib.PipelineRunner.new(this)
def imageID

node('blubber') {
  def blubberoidURL = "https://blubberoid.wikimedia.org/v1/"

  stage('Checkout SCM') {
    def patchset = plib.PatchSet.fromZuul(params)
    checkout(patchset.getSCM())
  }

  stage('Generate Dockerfile') {
    def blubber = plib.Blubber.new(this, '.pipeline/blubber.yaml', blubberoidURL)
    def dockerfile = blubber.generateDockerfile("test")

    echo 'Checking that Dockerfile was correctly generated'
    assert dockerfile.contains('LABEL blubber.variant="test"')
  }

  stage('Build test image') {
    imageID = prunner.build('test')
    echo 'Successfully built image "${imageID}" from "test" variant'
  }

  stage('Remove test image') {
    prunner.removeImage(imageID)
    echo 'Removed test image "${imageID}"'
  }
}
