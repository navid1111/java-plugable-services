package com.example.appbuilder.ui;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class UiControllerTest {

    @Test
    void servesAppBuilderUiAtRoot() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new UiController()).build();

        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"app-builder-ui\"")))
                .andExpect(content().string(containsString("/api/plugs")))
                .andExpect(content().string(containsString("/api/assess")))
                .andExpect(content().string(containsString("Generate App")));
    }
}
