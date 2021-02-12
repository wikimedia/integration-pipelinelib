import hudson.plugins.git.*
import hudson.model.ParametersDefinitionProperty
import hudson.model.StringParameterDefinition
import jenkins.model.Jenkins

import org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition
import org.jenkinsci.plugins.workflow.job.WorkflowJob

def scm = new GitSCM("/var/lib/git/repo1")
scm.branches = [new BranchSpec("master")]

def job = new WorkflowJob(Jenkins.instance, "repo1")
job.definition = new CpsScmFlowDefinition(scm, "Jenkinsfile")
job.addProperty(new ParametersDefinitionProperty(
  new StringParameterDefinition("PLIB_PIPELINE", "", "Pipeline to run")
))

Jenkins.instance.reload()
