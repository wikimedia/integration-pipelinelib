def config = jenkins.model.JenkinsLocationConfiguration.get()
config.setUrl("http://localhost:8080/")
config.save()
