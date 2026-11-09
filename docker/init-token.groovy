// Creates an API token for the "dev" user of the local test instance.
// The token value comes from JENKINS_TEST_TOKEN (see docker/setup_env.py), never from this file.
import hudson.model.User
import jenkins.security.ApiTokenProperty

def token = System.getenv('JENKINS_TEST_TOKEN')
if (!token) {
    println 'JENKINS_TEST_TOKEN is not set - skipping API token creation (run docker/setup_env.py first)'
    return
}

def user = User.getById('dev', true)
def property = user.getProperty(ApiTokenProperty)
if (property == null) {
    property = new ApiTokenProperty()
    user.addProperty(property)
}
def store = property.tokenStore
if (!store.tokenListSortedByName.any { it.name == 'jenkspotter' }) {
    // null expiration date = token never expires
    store.addFixedNewToken('jenkspotter', token, null)
    user.save()
}
