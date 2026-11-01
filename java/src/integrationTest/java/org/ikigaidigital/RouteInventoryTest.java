package org.ikigaidigital;

import org.ikigaidigital.testcontainers.PostgresContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.function.support.RouterFunctionMapping;
import org.springframework.web.servlet.handler.AbstractUrlHandlerMapping;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards ADR-0005 from the inside: the running context maps exactly the two contract operations and nothing else.
 * The HTTP probes in {@link RouteScopeEndToEndTest} can only try the paths someone thought of; this inventory also
 * catches a route nobody thought to probe. The same configuration as the probes, so the context is shared.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(PostgresContainerConfiguration.class)
class RouteInventoryTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void mapsExactlyTheTwoContractOperations() {
        Stream<String> routes = context.getBeansOfType(RequestMappingInfoHandlerMapping.class).values().stream()
                .flatMap(mapping -> mapping.getHandlerMethods().keySet().stream())
                .flatMap(info -> info.getMethodsCondition().getMethods().stream()
                        .flatMap(method -> info.getPatternValues().stream().map(path -> method + " " + path)));

        assertThat(routes).containsExactlyInAnyOrder("GET /api/v1/time-deposits", "POST /api/v1/balance-updates");
    }

    // Static resources, view controllers, favicon and welcome page would all live in URL-based mappings.
    @Test
    void registersNoUrlBasedRoutes() {
        assertThat(context.getBeansOfType(AbstractUrlHandlerMapping.class)).allSatisfy((name, mapping) -> {
            assertThat(mapping.getHandlerMap()).as(name).isEmpty();
            assertThat(mapping.getRootHandler()).as(name).isNull();
            assertThat(mapping.getDefaultHandler()).as(name).isNull();
        });
    }

    @Test
    void registersNoFunctionalRoutes() {
        assertThat(context.getBeansOfType(RouterFunctionMapping.class))
                .allSatisfy((name, mapping) -> assertThat(mapping.getRouterFunction()).as(name).isNull());
    }

    // A handler mapping of another kind could add routes this test cannot see, so it must be reviewed first.
    @Test
    void knowsEveryKindOfHandlerMapping() {
        assertThat(context.getBeansOfType(HandlerMapping.class)).allSatisfy((name, mapping) ->
                assertThat(mapping).as(name).isInstanceOfAny(RequestMappingInfoHandlerMapping.class,
                        AbstractUrlHandlerMapping.class, RouterFunctionMapping.class));
    }

    // Spring Boot's /error route is served by an ErrorController (BasicErrorController); there must be none.
    @Test
    void registersNoErrorController() {
        assertThat(context.getBeansOfType(ErrorController.class)).isEmpty();
    }
}
