package com.memo.docrank.webui;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Redirects root path to the Web Admin UI index page.
 */
@Controller
public class DocRankWebUiController {

    /** Redirect /docrank-ui → /docrank-ui/index.html */
    @GetMapping("/docrank-ui")
    public String uiRoot() {
        return "redirect:/docrank-ui/index.html";
    }
}
