package com.paymesh.controller;

import com.paymesh.dto.Experiment6PaymentRequest;
import com.paymesh.service.Experiment6Service;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/experiment6")
@CrossOrigin(origins = "http://localhost:5173")
public class Experiment6Controller {
    private final Experiment6Service service;
    public Experiment6Controller(Experiment6Service service) { this.service = service; }

    @GetMapping("/state") public Map<String,Object> state(){return service.state();}
    @GetMapping("/consistency") public Map<String,Object> consistency(){return service.checkConsistency();}
    @PostMapping("/reset") public Map<String,Object> reset(){return service.reset();}
    @PostMapping("/sync") public Map<String,Object> sync(@RequestBody Experiment6PaymentRequest request){return service.syncWrite(request);}
    @PostMapping("/async") public Map<String,Object> async(@RequestBody Experiment6PaymentRequest request){return service.asyncWrite(request);}
    @PostMapping("/replica/fail") public Map<String,Object> failReplica(){return service.failReplica();}
    @PostMapping("/replica/recover") public Map<String,Object> recoverReplica(){return service.recoverReplica();}
    @PostMapping("/resync") public Map<String,Object> resync(){return service.resync();}
    @PostMapping("/model/{model}") public Map<String,Object> model(@PathVariable String model){return service.setModel(model);}
    @GetMapping("/payments") public Map<String,Object> payments(){return service.payments();}
    @GetMapping("/events") public List<Map<String,Object>> events(){return service.events();}

    @ExceptionHandler({IllegalArgumentException.class,IllegalStateException.class})
    ResponseEntity<Map<String,String>> bad(RuntimeException e){return ResponseEntity.badRequest().body(Map.of("error",e.getMessage()==null?"Request failed":e.getMessage()));}
}
