import java.rmi.server.UnicastRemoteObject; import java.rmi.RemoteException; import java.text.NumberFormat; import java.util.Locale;
public class PaymentServiceImpl extends UnicastRemoteObject implements PaymentService {
 public PaymentServiceImpl() throws RemoteException { super(); }
 public String processPayment(String orderId,double amount,String method) throws RemoteException { String m=method.toUpperCase(); String processor=m.equals("CARD")?"Card-Processor":m.equals("NETBANKING")?"Banking-Processor":"UPI-Processor"; return "SUCCESS | Order="+orderId+" | Amount=₹"+NumberFormat.getNumberInstance(new Locale("en","IN")).format(amount)+" | Method="+m+" | Processor="+processor+" | Thread="+Thread.currentThread().getName(); }
}
