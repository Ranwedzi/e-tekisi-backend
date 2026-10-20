package com.etekisi.etekisi.integration;

import com.etekisi.etekisi.model.Driver;
import com.etekisi.etekisi.model.Role;
import com.etekisi.etekisi.repository.DriverRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import static com.etekisi.etekisi.integration.TestAuth.as;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The commuter map shows approximate positions of nearby online taxis, and nothing that identifies a driver. */
@SpringBootTest
class NearbyTaxisPrivacyTest {

    @Autowired private WebApplicationContext context;
    @Autowired private Fixtures fixtures;
    @Autowired private DriverRepository drivers;

    private void ping(MockMvc mvc, Driver d, double lat, double lng) throws Exception {
        mvc.perform(post("/api/locations/update").with(as(Role.DRIVER, d.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lat\":" + lat + ",\"lng\":" + lng + "}"))
                .andExpect(status().isOk());
    }

    @Test
    void onlyNearbyOnlineDriversAreShownRoundedAndWithoutIds() throws Exception {
        MockMvc mvc = TestAuth.mvc(context);
        // A remote spot so other tests' pings don't land in the radius.
        double lat = -33.912345, lng = 18.423456;

        Driver near = fixtures.driver();
        ping(mvc, near, lat + 0.004567, lng + 0.001234);          // ~500 m away
        Driver far = fixtures.driver();
        ping(mvc, far, lat + 0.2, lng);                            // ~22 km away
        Driver offline = fixtures.driver();
        ping(mvc, offline, lat + 0.001, lng + 0.001);
        offline.setActive(false);
        drivers.save(offline);

        mvc.perform(get("/api/locations/nearby").param("lat", "" + lat).param("lng", "" + lng)
                        .with(as(Role.COMMUTER, 1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].lat").value(-33.908))   // rounded to ~110 m
                .andExpect(jsonPath("$[0].lng").value(18.425))
                .andExpect(jsonPath("$[0].driverId").doesNotExist())
                .andExpect(jsonPath("$[*].lat", not(hasItem(lat + 0.2))));
    }

    @Test
    void callerPositionIsRequired() throws Exception {
        TestAuth.mvc(context).perform(get("/api/locations/nearby").with(as(Role.COMMUTER, 1L)))
                .andExpect(status().isBadRequest());
        TestAuth.mvc(context).perform(get("/api/locations/nearby").param("lat", "120").param("lng", "18")
                        .with(as(Role.COMMUTER, 1L)))
                .andExpect(status().isBadRequest());
    }
}
