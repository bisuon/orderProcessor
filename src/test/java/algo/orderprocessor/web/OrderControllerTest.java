package algo.orderprocessor.web;

import algo.orderprocessor.Order;
import algo.orderprocessor.web.dto.MetricsResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OrderService orderService;

    @MockBean
    private IdempotencyStore idempotencyStore;

    @Test
    void submitOrderIsAccepted() throws Exception {
        when(orderService.getSubmittedCount()).thenReturn(1L);
        when(idempotencyStore.lookup(anyString(), anyString())).thenReturn(IdempotencyStore.LookupResult.miss());

        mockMvc.perform(post("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\":\"premium-1\",\"premium\":true}"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.accepted").value(true))
            .andExpect(jsonPath("$.orderId").value("premium-1"))
            .andExpect(jsonPath("$.submittedCount").value(1));

        verify(orderService).submit(any(Order.class));
    }

    @Test
    void blankOrderIdReturns400() throws Exception {
        when(idempotencyStore.lookup(anyString(), anyString())).thenReturn(IdempotencyStore.LookupResult.miss());

        mockMvc.perform(post("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"premium\":true}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("orderId is required")));
    }

    @Test
    void submitAfterShutdownReturns409() throws Exception {
        doThrow(new IllegalStateException("OrderProcessor is shutting down; no new orders are accepted"))
            .when(orderService).submit(any(Order.class));
        when(idempotencyStore.lookup(anyString(), anyString())).thenReturn(IdempotencyStore.LookupResult.miss());

        mockMvc.perform(post("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\":\"late\",\"premium\":false}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("shutting down")));
    }

    @Test
    void idempotencyKeyPayloadConflictReturns409() throws Exception {
        when(idempotencyStore.lookup(anyString(), anyString())).thenReturn(IdempotencyStore.LookupResult.conflict());

        mockMvc.perform(post("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "same-key")
                .content("{\"orderId\":\"o1\",\"premium\":false}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Idempotency-Key")));
    }

    @Test
    void duplicateOrderIdReturns409() throws Exception {
        doThrow(new IllegalStateException("Order with id 'o-1' already exists"))
            .when(orderService).submit(any(Order.class));
        when(idempotencyStore.lookup(anyString(), anyString())).thenReturn(IdempotencyStore.LookupResult.miss());

        mockMvc.perform(post("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\":\"o-1\",\"premium\":true}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("already exists")));
    }

    @Test
    void metricsAreReturned() throws Exception {
        when(orderService.metrics()).thenReturn(new MetricsResponse(50, 50, 0, 0, 25, 25, true));

        mockMvc.perform(get("/api/v1/metrics"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.submitted").value(50))
            .andExpect(jsonPath("$.processed").value(50))
            .andExpect(jsonPath("$.failed").value(0))
            .andExpect(jsonPath("$.running").value(true));
    }

    @Test
    void invalidLimitReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/orders").param("limit", "0"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title").value("Validation failed"));
    }
}
