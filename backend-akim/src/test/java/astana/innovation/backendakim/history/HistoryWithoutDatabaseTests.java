package astana.innovation.backendakim.history;

import astana.innovation.backendakim.simulation.SimulationRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("standalone")
class HistoryWithoutDatabaseTests {
    @Autowired MockMvc mvc;

    @Test
    void exposesClearUnavailableResponsesWithoutAStorageProfile() throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\",\"password\":\"Password123\",\"username\":\"User\"}"))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\",\"password\":\"Password123\"}"))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isServiceUnavailable());
        mvc.perform(get("/api/v1/admin/me")).andExpect(status().isServiceUnavailable());
        mvc.perform(post("/api/v1/users/anonymous")).andExpect(status().isServiceUnavailable());
        mvc.perform(post("/api/v1/simulations").contentType(MediaType.APPLICATION_JSON)
                        .content(SimulationRequest.EXAMPLE_JSON))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(get("/api/v1/simulations")).andExpect(status().isServiceUnavailable());
        mvc.perform(get("/api/v1/simulations/11111111-1111-4111-8111-111111111111"))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(post("/api/simulation/calculate").contentType(MediaType.APPLICATION_JSON)
                        .content(SimulationRequest.EXAMPLE_JSON))
                .andExpect(status().isOk()).andExpect(jsonPath("$.finalScore").value(56.54307));
    }

    @Test
    void documentsAuthenticationWithoutProtectingThePreviewCalculator() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes.AnonymousBearer.scheme").value("bearer"))
                .andExpect(jsonPath("$.components.securitySchemes.BearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.paths['/api/v1/auth/register'].post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/me'].get.security[0].BearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/api/v1/simulations'].post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/simulations'].post.security[*].AnonymousBearer").exists())
                .andExpect(jsonPath("$.paths['/api/simulation/calculate'].post.security").doesNotExist());
    }
}
