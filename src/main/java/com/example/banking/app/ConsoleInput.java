package com.example.banking.app;

import java.io.Console;
import java.io.PrintStream;
import java.util.Scanner;

/** Reads keyboard input safely: hidden passwords when possible, clean exit on Ctrl+Z / Ctrl+D. */
public class ConsoleInput {

    /** Thrown when input ends (Ctrl+Z on Windows, Ctrl+D on Mac/Linux). */
    public static final class InputClosedException extends RuntimeException {
        public InputClosedException() {
            super("Input closed");
        }
    }

    private final PrintStream out;
    private final Console console;     // null when not running in a real terminal
    private final Scanner scanner;

    public ConsoleInput(PrintStream out) {
        this.out = out;
        Console c = System.console();
        this.console = (c != null && c.isTerminal()) ? c : null;
        this.scanner = (this.console == null) ? new Scanner(System.in) : null;
    }

    /** Reads one line, trimmed. */
    public String readLine(String prompt) {
        String line;
        if (console != null) {
             line = console.readLine("%s", prompt);
        } else {
            out.print(prompt);
            out.flush();
            line = scanner.hasNextLine() ? scanner.nextLine() : null;
        }
        if (line == null) {
            throw new InputClosedException();
        }
        return line.trim();
    }

    /** Reads a password. Hidden while typing when a real terminal is available. */
    public char[] readPassword(String prompt) {
        if (console != null) {
            char[] password = console.readPassword("%s", prompt);
            if (password == null) {
                throw new InputClosedException();
            }
            return password;
        }
        return readLine(prompt).toCharArray();
    }

    /** Asks until the user types a whole number between min and max. */
    public int readChoice(String prompt, int min, int max) {
        while (true) {
            String text = readLine(prompt);
            try {
                int value = Integer.parseInt(text);
                if (value >= min && value <= max) {
                    return value;
                }
            } catch (NumberFormatException ignored) {
                // fall through to the message below
            }
            out.println("Please enter a number from " + min + " to " + max + ".");
        }
    }
}