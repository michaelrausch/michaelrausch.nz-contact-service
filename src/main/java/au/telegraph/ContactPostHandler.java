package au.telegraph;

import au.telegraph.http.HttpContext;
import au.telegraph.http.HttpHandler;
import au.telegraph.configuration.models.AppConfig;
import au.telegraph.configuration.models.ClientConfiguration;
import au.telegraph.http.TelemetryData;
import au.telegraph.http.ratelimiter.RateLimiter;
import au.telegraph.messaging.Message;
import au.telegraph.messaging.MessageHandler;
import au.telegraph.messaging.exceptions.MessageSendException;
import au.telegraph.messaging.exceptions.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class ContactPostHandler implements HttpHandler {
    private static final String NAME = "name";
    private static final String MESSAGE = "message";
    private static final String EMAIL = "email";
    private static final String HONEYPOT = "email_h_v";
    private static final String CLIENT_ID = "realm";

    private final AppConfig configuration;
    private final RateLimiter rateLimiter;
    private final List<MessageHandler> messageHandlers;
    private static Logger logger = LoggerFactory.getLogger(ContactPostHandler.class.getName());
    private TelemetryData telemetryData = TelemetryData.getInstance();

    public ContactPostHandler(AppConfig config, RateLimiter rateLimiter){
        logger.info("Loading Contact Handler");

        this.configuration = config;
        this.messageHandlers = new ArrayList<>();
        this.rateLimiter = rateLimiter;
    }

    @Override
    public void Handle(HttpContext ctx) {
        List<String> requiredParams = getRequiredMessageParameters();
        Message message = new Message();
        ClientConfiguration activeClient;

        logger.debug("New Request from IP " + ctx.getIp());

        // Check required params exist
        if (!ctx.checkParamExists(requiredParams)) {
            logger.debug("Request missing required parameters ");
            ctx.badRequest();
            return;
        }


        // Honeypot check empty
        if (!ctx.checkParamExists(HONEYPOT) || !ctx.getFormParameter(HONEYPOT).equals("")){
            logger.warn("Honeypot Form Field Missing or not empty");

            ctx.badRequest();
            return;
        }

        // Abort if client id doesnt exist
        if (ctx.checkParamExists(CLIENT_ID) && !ctx.getFormParameter(CLIENT_ID).equals("")){
            String clientRealm = ctx.getFormParameter(CLIENT_ID);
            activeClient = getActiveClient(clientRealm);

            if (activeClient == null) {
                System.out.println("NAC");
                ctx.badRequest();
                return;
            }
        }
        else {
            ctx.badRequest();
            return;
        }

        // Try creating message object
        try {
            message.setName(ctx.getFormParameter(NAME))
                    .setMessage(ctx.getFormParameter(MESSAGE))
                    .setSenderAddress(ctx.getFormParameter(EMAIL));
        } catch (ValidationException e){
            logger.debug("Request validation failed ");
            ctx.setStatus(400);
            ctx.result(e.getMessage());
            return;
        }

        if (!rateLimiter.shouldAllowAccess(ctx.getIp())){
            ctx.setStatus(429);
            ctx.result("Rate Limit Exceeded");
            logger.info("Request from IP " + ctx.getIp() + " blocked, rate limit exceeded");
            return;
        }

        // Try running message handlers
        for (MessageHandler handler : messageHandlers) {
            try {
                handler.send(message, activeClient);
                logger.debug("Message forwarded to handler " + handler.toString());
            } catch (MessageSendException e) {
                ctx.serverError();
                logger.error("Handler threw exception " + e.getMessage());
                return;
            }
        }

        ctx.ok();
    }

    private ClientConfiguration getActiveClient(String clientRealm) {
        List<ClientConfiguration> clients = configuration.getClientList();

        for (ClientConfiguration c: clients) {
            if (c.getPublicKey().equals(clientRealm)){
                return c;
            }
        }

        return null;
    }

    /**
     * Replaces the current list of message handlers with the ones specified
     * @param messageHandlers A list of MessageHandlers
     */
    public void setMessageHandlers(List<MessageHandler> messageHandlers) {
        logger.debug("Setting new message handlers ");

        this.messageHandlers.clear();
        this.messageHandlers.addAll(messageHandlers);
    }

    /**
     * Add a message handler
     * @param messageHandler The message handler
     */
    public void addMessageHandler(MessageHandler messageHandler) {
        logger.debug("Adding new message handler {}", messageHandler.getClass().getName());
        this.messageHandlers.add(messageHandler);
    }

    /**
     * Returns a list of parameters required to be present in the form data
     * @return A List of required parameters
     */
    private List<String> getRequiredMessageParameters() {
        List<String> requiredParams = new ArrayList<>();

        requiredParams.add(NAME);
        requiredParams.add(EMAIL);
        requiredParams.add(MESSAGE);
        requiredParams.add(HONEYPOT);
        requiredParams.add(CLIENT_ID);

        return requiredParams;
    }
}
