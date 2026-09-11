import model.Message;
import model.Role;
import service.AIClient;

import java.util.Scanner;

public class Main {    public static void main(String[] args) {

        Scanner scanner = new Scanner(System.in);

        AIClient aiClient = new AIClient();

        System.out.println("------------------------------");
        System.out.println("       GuideIn + GEM API ");
        System.out.println("------------------------------");


        while (true) {

            System.out.print("\nYou: ");

            String userInput = scanner.nextLine();


            if (userInput.equalsIgnoreCase("exit")) {

                System.out.println("Ending conversation...");
                break;
            }


            Message userMessage = new Message(
                    Role.USER,
                    userInput
            );


            System.out.println("\nAI is thinking...\n");


            System.out.println("AI: " + aiClient.getReply(userMessage));
        }


        scanner.close();
    }
}