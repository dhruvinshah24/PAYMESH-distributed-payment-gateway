package com.paymesh.service;

import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class BullyElectionService {
  public record Terminal(String id, int priority, boolean alive) {}
  private final Map<String,Integer> priorities = Map.of("T1",10,"T2",25,"T3",40,"T4",15,"T5",30);
  private final Map<String,Boolean> alive = new LinkedHashMap<>();
  private final AtomicLong elections = new AtomicLong();
  private volatile String coordinator = "T3";
  public BullyElectionService(){ priorities.keySet().forEach(k -> alive.put(k,true)); }
  public synchronized Map<String,Object> snapshot(){
    List<Terminal> terminals=priorities.entrySet().stream().map(e->new Terminal(e.getKey(),e.getValue(),alive.get(e.getKey()))).sorted(Comparator.comparing(Terminal::id)).toList();
    return Map.of("algorithm","BULLY","coordinator",coordinator,"elections",elections.get(),"terminals",terminals,"timestamp",Instant.now().toString());
  }
  public synchronized Map<String,Object> startElection(){
    String initiator=coordinator;
    String winner=alive.entrySet().stream().filter(Map.Entry::getValue).max(Comparator.comparingInt(e->priorities.get(e.getKey()))).map(Map.Entry::getKey).orElse("NONE");
    coordinator=winner; elections.incrementAndGet();
    return Map.of("initiator",initiator,"winner",winner,"algorithm","BULLY","electionNumber",elections.get(),"terminals",snapshot().get("terminals"));
  }
  public synchronized void fail(String id){ if(alive.containsKey(id)) alive.put(id,false); if(id.equals(coordinator)) startElection(); }
  public synchronized void recover(String id){ if(alive.containsKey(id)) alive.put(id,true); }
  public synchronized void reset(){ priorities.keySet().forEach(k->alive.put(k,true)); coordinator="T3"; elections.set(0); }
}
