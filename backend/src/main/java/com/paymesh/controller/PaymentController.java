package com.paymesh.controller;

import com.paymesh.dto.PaymentRequest;
import com.paymesh.service.PaymentService;
import com.paymesh.service.BullyElectionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "http://localhost:5173")
public class PaymentController {
    private final PaymentService service;
    private final BullyElectionService bully;
    public PaymentController(PaymentService service, BullyElectionService bully){this.service=service;this.bully=bully;}

    @GetMapping("/health") public Map<String,Object> health(){return Map.of("service","PAYMESH","status","UP");}
    @PostMapping("/payments") public Map<String,Object> create(@RequestBody PaymentRequest request){return service.create(request);}
    @GetMapping("/payments") public List<Map<String,Object>> payments(){return service.payments();}
    @GetMapping("/payments/{id}") public Map<String,Object> payment(@PathVariable String id){return service.payment(id);}
    @GetMapping("/state") public Map<String,Object> state(){return service.state();}
    @GetMapping("/consistency") public Map<String,Object> consistency(){return service.consistency();}
    @GetMapping("/events") public List<Map<String,Object>> events(){return service.events();}
    @GetMapping("/ledger/verify") public Map<String,Object> ledger(){return service.ledgerVerification();}
    @GetMapping("/clock-sync") public Map<String,Object> clock(){return service.clockSync();}
    @GetMapping("/settlement") public Map<String,Object> settlement(){return service.settlement();}
    @GetMapping("/election") public Map<String,Object> election(){return bully.snapshot();}
    @PostMapping("/election/start") public Map<String,Object> electionStart(){return bully.startElection();}
    @PostMapping("/experiment/5/run") public Map<String,Object> experiment5(){return service.runExperiment5();}
    @PostMapping("/control/{action}") public Map<String,Object> control(@PathVariable String action){return service.control(action);}

    @ExceptionHandler({IllegalArgumentException.class,IllegalStateException.class,NoSuchElementException.class})
    ResponseEntity<Map<String,String>> bad(RuntimeException e){return ResponseEntity.badRequest().body(Map.of("error",e.getMessage()==null?"Request failed":e.getMessage()));}
}
