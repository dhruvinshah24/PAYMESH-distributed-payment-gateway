import java.rmi.Remote; import java.rmi.RemoteException;
public interface PaymentService extends Remote { String processPayment(String orderId,double amount,String method) throws RemoteException; }
