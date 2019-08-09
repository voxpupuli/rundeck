package webhooks

import com.dtolabs.rundeck.core.authorization.AuthContext
import com.dtolabs.rundeck.core.authorization.AuthorizationUtil
import com.dtolabs.rundeck.core.authorization.UserAndRolesAuthContext
import com.dtolabs.rundeck.core.webhook.WebhookEventException
import com.dtolabs.rundeck.plugins.webhook.WebhookData
import com.fasterxml.jackson.databind.ObjectMapper
import grails.converters.JSON
import groovy.transform.PackageScope
import webhooks.Webhook

import javax.servlet.http.HttpServletResponse
import static webhooks.WebhookConstants.*

class WebhookController {
    private static final ObjectMapper mapper = new ObjectMapper()

    def webhookService
    def frameworkService
    def apiService

    def index() { }

    def save() {
        String project = request.JSON.project
        if(!project){
            return apiService.renderErrorFormat(response, [status: HttpServletResponse.SC_BAD_REQUEST,
                                                           code: 'api.error.parameter.required', args: ['project']])

        }
        UserAndRolesAuthContext authContext = frameworkService.getAuthContextForSubject(session.subject)
        if (!frameworkService.authorizeProjectResourceAny(authContext,RESOURCE_TYPE_WEBHOOK, [ACTION_CREATE,ACTION_UPDATE],project)) {
            sendJsonError("You are not authorized to perform this action")
            return
        }

        def msg = webhookService.saveHook(authContext,request.JSON)
        if(msg.err) response.status = 500

        render msg as JSON
    }

    def remove() {
        Webhook webhook = webhookService.getWebhook(params.id.toLong())
        if(!webhook) {
            sendJsonError("Webhook not found")
            return
        }

        UserAndRolesAuthContext authContext = frameworkService.getAuthContextForSubject(session.subject)
        if (!authorized(authContext, webhook.project, RESOURCE_TYPE_WEBHOOK, ACTION_DELETE)) {
            sendJsonError("You are not authorized to perform this action")
            return
        }
        def output = webhookService.delete(webhook)
        if(output.err) response.status = 500
        render output as JSON

    }

    def list() {
        if(!params.project){
            return apiService.renderErrorFormat(response, [status: HttpServletResponse.SC_BAD_REQUEST,
                                                           code: 'api.error.parameter.required', args: ['project']])

        }
        UserAndRolesAuthContext authContext = frameworkService.getAuthContextForSubject(session.subject)
        if (!authorized(authContext, params.project, RESOURCE_TYPE_WEBHOOK, ACTION_READ)) {
            sendJsonError("You do not have access to this resource")
            return
        }
        render webhookService.listWebhooksByProject(params.project) as JSON
    }

    def editorData() {
        if(!params.project){
            return apiService.renderErrorFormat(response, [status: HttpServletResponse.SC_BAD_REQUEST,
                                                           code: 'api.error.parameter.required', args: ['project']])

        }
        UserAndRolesAuthContext authContext = frameworkService.getAuthContextForSubject(session.subject)
        if (!authorized(authContext, params.project, RESOURCE_TYPE_WEBHOOK, ACTION_READ)) {
            sendJsonError("You do not have access to this resource")
            return
        }

        def uidata = [:]
        uidata.hooks = webhookService.listWebhooksByProject(params.project)
        uidata.username = authContext.username
        uidata.roles = authContext.roles.join(",")
        render uidata as JSON
    }

    def post() {
        String token = params.authtoken
        Webhook hook = webhookService.getWebhookByToken(token)

        if(!hook) {
            sendJsonError("Webhook not found")
            return
        }

        UserAndRolesAuthContext authContext = frameworkService.getAuthContextForSubject(session.subject)
        if (!authorized(authContext, hook.project, RESOURCE_TYPE_WEBHOOK, ACTION_POST)) {
            sendJsonError("You are not authorized to perform this action")
            return
        }

        WebhookData whkdata = new WebhookData()
        whkdata.webhook = hook.name
        whkdata.timestamp = System.currentTimeMillis()
        whkdata.sender = request.remoteAddr
        whkdata.project = hook.project
        whkdata.contentType = request.contentType
        whkdata.data = request.inputStream

        try {
            webhookService.processWebhook(hook.eventPlugin, hook.pluginConfigurationJson, whkdata, authContext)
            render new HashMap([msg: "ok"]) as JSON
        } catch(WebhookEventException wee) {
            sendJsonError(wee.message)
        }
    }

    private def sendJsonError(String errMessage) {
        response.setStatus(400)
        def err = [err:errMessage]
        render err as JSON
    }

    @PackageScope
    boolean authorized(AuthContext authContext, String project, Map resourceType = ADMIN_RESOURCE,String action = ACTION_ADMIN) {
        List authorizedActions = [ACTION_ADMIN]
        if(action != ACTION_ADMIN) authorizedActions.add(action)
        frameworkService.authorizeProjectResourceAny(authContext,resourceType,authorizedActions,project)
    }

    private static Map ADMIN_RESOURCE = Collections.unmodifiableMap(AuthorizationUtil.resourceType("admin"))
}
