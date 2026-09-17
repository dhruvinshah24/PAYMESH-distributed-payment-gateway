import java.io.*;
import java.net.*;
import java.util.Scanner;

public class RPCClient {
    public static void main(String[] args) throws Exception {
        Scanner sc=new Scanner(System.in);
        System.out.println("\n============================================");
        System.out.println(" PAYMESH — TCP RPC PAYMENT CLIENT");
        System.out.println("============================================");
        System.out.print("Order ID: "); String order=sc.nextLine();
        System.out.print("Amount (₹): "); String amount=sc.nextLine().trim().replace(",","");
        System.out.println("\n1. Card\n2. UPI\n3. Net Banking");
        System.out.print("Choose payment method: "); String c=sc.nextLine();
        String method=switch(c){case "1"->"CARD";case "3"->"NETBANKING";default->"UPI";};
        try(Socket socket=new Socket("localhost",7000)){
            PrintWriter out=new PrintWriter(socket.getOutputStream(),true);
            BufferedReader in=new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out.println(order+"|"+amount+"|"+method);
            System.out.println("\nRPC response: "+in.readLine());
        }
        sc.close();
    }
}
