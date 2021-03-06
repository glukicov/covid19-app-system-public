package uk.nhs.nhsx.virology;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import uk.nhs.nhsx.core.Environment;
import uk.nhs.nhsx.core.EnvironmentKeys;
import uk.nhs.nhsx.core.HttpResponses;
import uk.nhs.nhsx.core.Jackson;
import uk.nhs.nhsx.core.SystemClock;
import uk.nhs.nhsx.core.auth.ApiName;
import uk.nhs.nhsx.core.auth.Authenticator;
import uk.nhs.nhsx.core.auth.ResponseSigner;
import uk.nhs.nhsx.core.routing.Routing;
import uk.nhs.nhsx.core.routing.Routing.*;
import uk.nhs.nhsx.core.routing.RoutingHandler;
import uk.nhs.nhsx.virology.exchange.CtaExchangeRequest;
import uk.nhs.nhsx.virology.lookup.VirologyLookupRequest;
import uk.nhs.nhsx.virology.order.VirologyRequestType;
import uk.nhs.nhsx.virology.order.TokensGenerator;
import uk.nhs.nhsx.virology.order.VirologyWebsiteConfig;
import uk.nhs.nhsx.virology.persistence.VirologyDynamoService;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

import static uk.nhs.nhsx.core.Environment.EnvironmentKey.string;
import static uk.nhs.nhsx.core.Jackson.deserializeMaybe;
import static uk.nhs.nhsx.core.Jackson.deserializeMaybeLogInfo;
import static uk.nhs.nhsx.core.StandardSigning.signResponseWithKeyGivenInSsm;
import static uk.nhs.nhsx.core.auth.StandardAuthentication.awsAuthentication;
import static uk.nhs.nhsx.core.routing.Routing.*;
import static uk.nhs.nhsx.core.routing.StandardHandlers.withSignedResponses;

/**
 * Test kit order Lambda and test result polling Lambda.
 * <p>
 * see /doc/design/api-contracts/virology-testing-api.md
 * see /doc/design/details/testkit-order-test-result-key-upload.md
 * <p>
 * Sample:
 * <pre>
 * $ rake secret:createmobile
 * ...
 * "Authorization": "Bearer [token]"
 * ...
 *
 * $ curl -v -H "Content-Type: application/json"  -H "Authorization: Bearer [token]" -d '' https://w9z3i7j656.execute-api.eu-west-2.amazonaws.com/virology-test/home-kit/order
 * {"websiteUrlWithQuery":"https://self-referral.test-for-coronavirus.service.gov.uk/cta-start?ctaToken=620466","tokenParameterValue":"620466","testResultPollingToken":"98cff3dd-882c-417b-a00a-350a205378c7","diagnosisKeySubmissionToken":"cf492966-756a-4ae0-b66e-bf728d72aa43"}* Closing connection 0
 *
 *
 * $ curl -v -H "Authorization: Bearer [token]" -H "Content-Type: application/json" -d '{"testResultPollingToken":"98cff3dd-882c-417b-a00a-350a205378c7"}' https://w9z3i7j656.execute-api.eu-west-2.amazonaws.com/virology-test/results
 * HTTP/2 204
 *
 * test result upload (see uk.nhs.nhsx.virology.VirologyUploadHandler for sample)
 *
 * $ curl -v -H "Authorization: Bearer [token] -H "Content-Type: application/json" -d '{"testResultPollingToken":"98cff3dd-882c-417b-a00a-350a205378c7"}' https://w9z3i7j656.execute-api.eu-west-2.amazonaws.com/virology-test/results
 * {"testEndDate":"2020-04-23T18:34:03Z","testResult":"POSITIVE"}
 * </pre>
 */
public class VirologySubmissionHandler extends RoutingHandler {

    private final Routing.Handler handler;

    private static final Logger logger = LogManager.getLogger(VirologySubmissionHandler.class);
    private static final Duration defaultDelayDuration = Duration.ofSeconds(1);

    public VirologySubmissionHandler() {
        this(Environment.fromSystem(), SystemClock.CLOCK, defaultDelayDuration);
    }

    public VirologySubmissionHandler(Environment environment, Supplier<Instant> clock, Duration throttleDuration) {
        this(
            environment, 
            awsAuthentication(ApiName.Mobile),
            signResponseWithKeyGivenInSsm(clock, environment),
            virologyService(clock, environment),
            websiteConfig(environment),
            throttleDuration
        );
    }

    public VirologySubmissionHandler(Environment environment,
                                     Authenticator authenticator,
                                     ResponseSigner signer,
                                     VirologyService service,
                                     VirologyWebsiteConfig websiteConfig,
                                     Duration delayDuration) {
        handler = withSignedResponses(
            environment,
            authenticator,
            signer,
            routes(
                path(Method.POST, "/virology-test/home-kit/order", (r) ->
                    handleVirologyOrder(service, websiteConfig, VirologyRequestType.ORDER)),
                path(Method.POST, "/virology-test/home-kit/register", (r) ->
                    handleVirologyOrder(service, websiteConfig, VirologyRequestType.REGISTER)),
                path(Method.POST, "/virology-test/results", (r) ->
                    deserializeMaybe(r.getBody(), VirologyLookupRequest.class)
                        .map(it -> service.virologyLookupFor(it).toHttpResponse())
                        .orElse(HttpResponses.unprocessableEntity())),
                path(Method.POST, "/virology-test/cta-exchange", (r) ->
                    throttlingResponse(
                        delayDuration,
                        () -> deserializeMaybeLogInfo(r.getBody(), CtaExchangeRequest.class)
                            .map(it -> service.exchangeCtaToken(it).toHttpResponse())
                            .orElseGet(HttpResponses::badRequest)
                    )
                ),
                path(Method.POST, "/virology-test/health", (r) ->
                    HttpResponses.ok()
                )
            )
        );
    }

    private APIGatewayProxyResponseEvent handleVirologyOrder(VirologyService service,
                                                             VirologyWebsiteConfig websiteConfig,
                                                             VirologyRequestType order) {
        var response = service.handleTestOrderRequest(websiteConfig, order);
        logger.info(
            "Virology order created ctaToken: {}, testResultToken: {}",
            response.tokenParameterValue, response.testResultPollingToken
        );
        return HttpResponses.ok(Jackson.toJson(response));
    }

    private static VirologyService virologyService(Supplier<Instant> clock, Environment environment) {
        return new VirologyService(
            new VirologyDynamoService(
                AmazonDynamoDBClientBuilder.defaultClient(),
                virologyConfig(environment)
            ),
            new TokensGenerator(),
            clock
        );
    }

    private static final Environment.EnvironmentKey<String> TEST_ORDERS_TABLE = string("test_orders_table");
    private static final Environment.EnvironmentKey<String> TEST_RESULTS_TABLE = string("test_results_table");
    private static final Environment.EnvironmentKey<String> TEST_ORDERS_INDEX = string("test_orders_index");
    
    private static VirologyConfig virologyConfig(Environment environment) {
        return new VirologyConfig(
            environment.access.required(TEST_ORDERS_TABLE),
            environment.access.required(TEST_RESULTS_TABLE),
            environment.access.required(EnvironmentKeys.SUBMISSIONS_TOKENS_TABLE),
            environment.access.required(TEST_ORDERS_INDEX),
            VirologyConfig.MAX_TOKEN_PERSISTENCE_RETRY_COUNT
        );
    }

    private static final Environment.EnvironmentKey<String> ORDER_WEBSITE = string("order_website");
    private static final Environment.EnvironmentKey<String> REGISTER_WEBSITE = string("register_website");

    private static VirologyWebsiteConfig websiteConfig(Environment environment) {
        return new VirologyWebsiteConfig(
            environment.access.required(ORDER_WEBSITE),
            environment.access.required(REGISTER_WEBSITE)
        );
    }

    @Override
    public Routing.Handler handler() {
        return handler;
    }
}
