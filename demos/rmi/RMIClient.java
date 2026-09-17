import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.Scanner;

public class RMIClient {
    static String readAmount(Scanner sc) {
        while (true) {
            try { System.out.print("Amount (₹): "); return String.valueOf(Double.parseDouble(sc.nextLine().trim().replace(",", ""))); }
            catch (NumberFormatException e) { System.out.println("Enter a valid amount, e.g. 45,000"); }
        }
    }
    public static void main(String[] args) {
        try {
            Registry registry = LocateRegistry.getRegistry("localhost", 1099);
            PaymentService service = (PaymentService) registry.lookup("PaymentService");
            Scanner sc = new Scanner(System.in);
            System.out.println("\n============================================");
            System.out.println(" PAYMESH — RMI PAYMENT CLIENT");
            System.out.println("============================================");
            System.out.print("Order ID: "); String order = sc.nextLine();
            String amount = readAmount(sc);
            System.out.println("\n1. Card\n2. UPI\n3. Net Banking");
            System.out.print("Choose payment method: "); String choice=sc.nextLine();
            String method = switch(choice){case "1"->"CARD";case "3"->"NETBANKING";default->"UPI";};
            System.out.println("\nSending remote call to PaymentService...");
            System.out.println("Response: " + service.processPayment(order, Double.parseDouble(amount), method));
            System.out.println("Formatted amount: ₹" + NumberFormat.getNumberInstance(new Locale("en","IN")).format(Double.parseDouble(amount)));
            sc.close();
        } catch (Exception e) { System.out.println("RMI client error: " + e.getMessage()); }
    }
}
