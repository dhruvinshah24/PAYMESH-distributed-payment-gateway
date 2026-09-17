import java.util.*;

/** Educational 2PC simulation: coordinator + payment participants. */
public class TwoPhaseCommitDemo {
    static class Participant { final String name; boolean prepared=true; Participant(String n){name=n;} boolean prepare(){System.out.println(name+" -> PREPARED");return prepared;} void commit(){System.out.println(name+" -> COMMIT");} void rollback(){System.out.println(name+" -> ROLLBACK");} }
    public static void main(String[] args){
        System.out.println("============================================");
        System.out.println(" PAYMESH — TWO-PHASE COMMIT DEMO");
        System.out.println("============================================");
        List<Participant> ps=List.of(new Participant("Payment DB"),new Participant("Ledger"),new Participant("Settlement"));
        boolean all=true; System.out.println("Phase 1: PREPARE"); for(Participant p:ps) all &= p.prepare();
        System.out.println("Coordinator decision: "+(all?"COMMIT":"ROLLBACK"));
        System.out.println("Phase 2: "+(all?"COMMIT":"ROLLBACK")); for(Participant p:ps) {if(all)p.commit();else p.rollback();}
        System.out.println("2PC demo complete.");
    }
}
