package com.agent.controller;

import com.agent.service.AgentService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Controller
public class AgentController {

    private final AgentService agentService;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    @GetMapping("/")
    public String index() {
        return "index";
    }

    @GetMapping("/events")
    public SseEmitter events() {
        return agentService.subscribe();
    }

    @PostMapping("/agent/start")
    @ResponseBody
    public Map<String, Object> start() {
        Map<String, Object> status = agentService.start();
        executorService.execute(agentService::runCycle);
        return status;
    }

    @PostMapping("/agent/stop")
    @ResponseBody
    public Map<String, Object> stop() {
        return agentService.stop();
    }

    @GetMapping("/agent/status")
    @ResponseBody
    public Map<String, Object> status() {
        return agentService.status();
    }
}
