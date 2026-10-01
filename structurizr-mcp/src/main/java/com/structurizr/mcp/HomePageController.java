package com.structurizr.mcp;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
public class HomePageController {

    @RequestMapping("/")
    @org.springframework.web.bind.annotation.ResponseBody
    public String homePage() {
        return "Structurizr MCP - POST /mcp";
    }

}