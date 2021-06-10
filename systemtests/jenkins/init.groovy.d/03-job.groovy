import hudson.plugins.git.*
import hudson.model.ParameterDefinition
import hudson.model.ParametersDefinitionProperty
import hudson.model.StringParameterDefinition
import jenkins.model.Jenkins

import org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition
import org.jenkinsci.plugins.workflow.job.WorkflowJob

// repo1 job
def scm = new GitSCM("/var/lib/git/repo1")
scm.branches = [new BranchSpec("master")]

def job = new WorkflowJob(Jenkins.instance, "repo1")
job.definition = new CpsScmFlowDefinition(scm, "Jenkinsfile")
job.addProperty(new ParametersDefinitionProperty(
  new StringParameterDefinition("PLIB_PIPELINE", "", "Pipeline to run")
))

// totally-triggered job
def project = new hudson.model.FreeStyleProject(Jenkins.instance, "totally-triggered")
project.setDescription("A job to be triggered by another")

def parameterDefinitions = new ArrayList<ParameterDefinition>();
parameterDefinitions.add(new StringParameterDefinition("TRIGGER", "life", "The thing we're totally triggered by"))

project.addProperty(new ParametersDefinitionProperty(parameterDefinitions))
project.buildersList.add(new hudson.tasks.Shell('''
echo "OMG I\'m like totally triggered by ${TRIGGER}"
echo I need to take a moment.
sleep 20
'''))
project.save()

Jenkins.instance.reload()
