package io.github.electroheavenvn.zaloxposed.utilities.bot;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.electroheavenvn.zaloxposed.utilities.CoreUtilityHelper;

/**
 * Command parsing and reply generation for the optional chat command engine.
 * The engine has no dependency on Zalo internals: callers supply a message and
 * send the returned replies through their own bridge.
 */
public final class BotCommandEngine
{
    /** One incoming message, already normalised by the hook. */
    public static final class Incoming
    {
        public final String threadId;
        public final String senderId;
        public final String senderName;
        public final String content;
        public final long timestamp;

        public Incoming(String threadId, String senderId, String senderName, String content, long timestamp)
        {
            this.threadId = threadId == null ? "" : threadId;
            this.senderId = senderId == null ? "" : senderId;
            this.senderName = senderName == null || senderName.isEmpty() ? this.senderId : senderName;
            this.content = content == null ? "" : content.trim();
            this.timestamp = timestamp;
        }
    }

    private static final long START_MS = System.currentTimeMillis();
    private static final long MAX_AGE_MS = 30_000L;
    private static final long DEFAULT_COOLDOWN_MS = 1_200L;
    private static final int MAX_REPEATS = 5;

    private static final Random RANDOM = new Random();
    private static final Map<String, Long> LAST_USED = new ConcurrentHashMap<>();
    private static final Map<String, Long> SEEN = new ConcurrentHashMap<>();
    private static final Map<String, Set<String>> NAMES = new ConcurrentHashMap<>();
    private static final Set<String> MODERATED = ConcurrentHashMap.newKeySet();

    private static final String[] BALL_ANSWERS = {
        "Yes", "No", "Maybe", "Ask again later", "Very likely", "Doubtful", "Cannot tell"
    };
    private static final String[] FACTS = {
        "Water boils at 100 degrees Celsius at sea level.",
        "A day on Venus is longer than its year.",
        "Honey does not spoil in normal conditions.",
        "Octopuses have three hearts.",
        "The shortest war on record lasted under an hour."
    };
    private static final String[] COMMANDS = {
        "help", "ping", "time", "dice", "coin", "8ball", "fact", "pick", "calc",
        "uptime", "say", "tagall", "role", "spam", "setcmd", "prefix", "info",
        "members", "roll", "sys", "clearbot", "mute", "unmute", "mutelist",
        "block", "unblock"
    };

    private BotCommandEngine() { }

    /** Keeps a small per-thread name cache used by tagall and members. */
    public static void remember(String threadId, String senderId, String senderName)
    {
        if (threadId == null || senderId == null || senderId.isEmpty())
            return;
        Set<String> names = NAMES.computeIfAbsent(threadId, key -> Collections.synchronizedSet(new LinkedHashSet<>()));
        synchronized (names)
        {
            names.add(senderName == null || senderName.isEmpty() ? senderId : senderName);
            while (names.size() > 50)
                names.remove(names.iterator().next());
        }
    }

    /** Returns true when a message is a fresh command that should be handled. */
    public static boolean isCommand(Incoming message)
    {
        if (message == null || message.content.isEmpty() || message.senderId.isEmpty())
            return false;
        String prefix = BotStore.getPrefix();
        if (!message.content.startsWith(prefix))
            return false;
        long now = System.currentTimeMillis();
        if (message.timestamp > 0 && now - message.timestamp > MAX_AGE_MS)
            return false;
        String self = CoreUtilityHelper.GetCurrentUserID();
        if (self != null && !self.isEmpty() && !"0".equals(self) && self.equals(message.senderId))
            return false;
        if (BotStore.getRole(message.senderId) <= 0)
            return false;
        String key = message.threadId + '|' + message.senderId + '|' + message.content.hashCode() + '|' + message.timestamp;
        Long previous = SEEN.put(key, now);
        if (previous != null && now - previous < 60_000L)
            return false;
        if (SEEN.size() > 1_000)
            SEEN.clear();
        return true;
    }

    /** Runs one command and returns zero or more reply texts. */
    public static List<String> execute(Incoming message)
    {
        String prefix = BotStore.getPrefix();
        String body = message.content.substring(prefix.length()).trim();
        if (body.isEmpty())
            return Collections.singletonList("Type " + prefix + "help to see the commands.");
        int split = body.indexOf(' ');
        String command = (split < 0 ? body : body.substring(0, split)).toLowerCase(Locale.ROOT);
        String args = split < 0 ? "" : body.substring(split + 1).trim();

        long cooldown = cooldownFor(command);
        String rateKey = message.threadId + '|' + message.senderId + '|' + command;
        long now = System.currentTimeMillis();
        Long last = LAST_USED.get(rateKey);
        if (last != null && now - last < cooldown)
            return Collections.emptyList();
        LAST_USED.put(rateKey, now);
        if (LAST_USED.size() > 500)
            LAST_USED.clear();

        String reply = run(command, args, message);
        if (reply == null || reply.isEmpty())
            return Collections.emptyList();
        return splitReplies(reply);
    }

    private static long cooldownFor(String command)
    {
        switch (command)
        {
            case "spam":
                return 60_000L;
            case "say":
            case "tagall":
                return 10_000L;
            case "setcmd":
            case "prefix":
            case "clearbot":
            case "mute":
            case "unmute":
            case "mutelist":
            case "block":
            case "unblock":
                return 3_000L;
            default:
                return DEFAULT_COOLDOWN_MS;
        }
    }

    private static String run(String command, String args, Incoming message)
    {
        switch (command)
        {
            case "help":
                return "Commands: " + String.join(", ", COMMANDS);
            case "ping":
                return "pong";
            case "time":
                return new SimpleDateFormat("HH:mm:ss dd/MM/yyyy", Locale.getDefault()).format(new Date());
            case "dice":
                return "Dice: " + (RANDOM.nextInt(6) + 1);
            case "roll":
                int sides = parsePositive(args, 100);
                return "Roll: " + (RANDOM.nextInt(sides) + 1);
            case "coin":
                return RANDOM.nextBoolean() ? "Heads" : "Tails";
            case "8ball":
                return BALL_ANSWERS[RANDOM.nextInt(BALL_ANSWERS.length)];
            case "fact":
                return FACTS[RANDOM.nextInt(FACTS.length)];
            case "pick":
                return pick(args);
            case "calc":
                try
                {
                    return formatNumber(eval(args));
                }
                catch (RuntimeException e)
                {
                    return "Could not calculate that expression.";
                }
            case "uptime":
                return "Uptime: " + duration(System.currentTimeMillis() - START_MS);
            case "say":
                return limit(args);
            case "tagall":
                return tagAll(message.threadId);
            case "members":
                return "Known members: " + names(message.threadId).size();
            case "info":
                return "Thread: " + safe(message.threadId) + " | Sender: " + safe(message.senderId);
            case "sys":
                return "Java " + System.getProperty("java.version") + " | " + duration(System.currentTimeMillis() - START_MS);
            case "role":
                return role(args, message);
            case "spam":
                int count = Math.min(MAX_REPEATS, Math.max(1, parsePositive(args, 1)));
                StringBuilder spam = new StringBuilder();
                for (int i = 0; i < count; i++)
                    spam.append("ping").append(i + 1 < count ? "\n" : "");
                return spam.toString();
            case "setcmd":
                return setCommand(args, message);
            case "prefix":
                return setPrefix(args, message);
            case "clearbot":
                if (!isOperator(message))
                    return null;
                BotStore.clearCustoms();
                MODERATED.clear();
                return "Custom commands cleared.";
            case "mute":
            case "block":
                return moderate(args, message, 0, "Muted ");
            case "unmute":
            case "unblock":
                return moderate(args, message, 1, "Unmuted ");
            case "mutelist":
                if (MODERATED.isEmpty())
                    return "No muted members.";
                return "Muted: " + String.join(", ", MODERATED);
            default:
                String custom = BotStore.getCustom(command);
                return custom == null ? null : custom;
        }
    }

    /** Local moderation: role 0 blocks commands from that sender. */
    private static String moderate(String args, Incoming message, int role, String verb)
    {
        if (!isOperator(message))
            return null;
        String userId = args.trim();
        if (userId.isEmpty())
            return "Usage: " + verb.trim().toLowerCase(Locale.ROOT) + " <user id>";
        BotStore.setRole(userId, role);
        if (role == 0)
            MODERATED.add(userId);
        else
            MODERATED.remove(userId);
        return verb + safe(userId);
    }

    private static String pick(String args)
    {
        if (args.isEmpty())
            return "Give me something to pick from.";
        String[] choices = args.contains(",") ? args.split(",") : args.split("\\s+");
        List<String> cleaned = new ArrayList<>();
        for (String choice : choices)
        {
            String value = choice.trim();
            if (!value.isEmpty())
                cleaned.add(value);
        }
        if (cleaned.isEmpty())
            return "Give me something to pick from.";
        return "Picked: " + cleaned.get(RANDOM.nextInt(cleaned.size()));
    }

    private static String role(String args, Incoming message)
    {
        if (args.isEmpty())
            return "Your role: " + BotStore.getRole(message.senderId);
        if (!isOperator(message))
            return null;
        String[] parts = args.split("\\s+");
        if (parts.length < 2)
            return "Usage: role <user> <0|1|3>";
        try
        {
            int level = Integer.parseInt(parts[1]);
            if (level < 0 || level > 3)
                return "Role must be 0, 1 or 3.";
            BotStore.setRole(parts[0], level);
            if (level == 0)
                MODERATED.add(parts[0]);
            else
                MODERATED.remove(parts[0]);
            return "Role updated.";
        }
        catch (NumberFormatException e)
        {
            return "Role must be 0, 1 or 3.";
        }
    }

    private static String setCommand(String args, Incoming message)
    {
        if (!isOperator(message))
            return null;
        int split = args.indexOf(' ');
        if (split <= 0)
            return "Usage: setcmd <name> <response>";
        BotStore.setCustom(args.substring(0, split), limit(args.substring(split + 1)));
        return "Command saved.";
    }

    private static String setPrefix(String args, Incoming message)
    {
        if (!isOperator(message))
            return null;
        String prefix = args.trim();
        if (prefix.isEmpty() || prefix.length() > 4 || prefix.matches(".*\\s.*"))
            return "Prefix must be 1 to 4 non-space characters.";
        BotStore.setPrefix(prefix);
        return "Prefix is now " + prefix;
    }

    private static boolean isOperator(Incoming message)
    {
        return BotStore.getRole(message.senderId) >= 3;
    }

    private static String tagAll(String threadId)
    {
        Set<String> names = names(threadId);
        if (names.isEmpty())
            return "No known members in this conversation yet.";
        List<String> limited = new ArrayList<>();
        synchronized (names)
        {
            for (String name : names)
            {
                if (limited.size() >= 20)
                    break;
                limited.add("@" + name);
            }
        }
        return String.join(" ", limited);
    }

    private static Set<String> names(String threadId)
    {
        Set<String> names = NAMES.get(threadId);
        return names == null ? Collections.emptySet() : names;
    }

    private static List<String> splitReplies(String reply)
    {
        String[] lines = reply.split("\\n");
        List<String> result = new ArrayList<>();
        for (String line : lines)
        {
            if (!line.trim().isEmpty())
                result.add(limit(line));
        }
        return result;
    }

    private static String limit(String value)
    {
        String safe = value == null ? "" : value.trim();
        return safe.length() > 200 ? safe.substring(0, 200) : safe;
    }

    private static String safe(String value)
    {
        return value == null || value.isEmpty() ? "-" : value;
    }

    private static int parsePositive(String value, int fallback)
    {
        try
        {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : fallback;
        }
        catch (RuntimeException ignored)
        {
            return fallback;
        }
    }

    private static String formatNumber(double value)
    {
        if (Double.isNaN(value) || Double.isInfinite(value))
            return "Invalid result";
        if (value == Math.rint(value) && Math.abs(value) < 1e15)
            return Long.toString((long)value);
        return String.format(Locale.ROOT, "%.4f", value).replaceAll("0+$", "");
    }

    private static String duration(long millis)
    {
        long seconds = Math.max(0, millis / 1000);
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        return hours + "h " + minutes + "m " + (seconds % 60) + "s";
    }

    /** Minimal arithmetic evaluator: + - * / % and parentheses. */
    private static double eval(String expression) throws RuntimeException
    {
        if (expression == null || expression.trim().isEmpty())
            throw new IllegalArgumentException("empty");
        Parser parser = new Parser(expression);
        double value = parser.expression();
        if (!parser.atEnd())
            throw new IllegalArgumentException("trailing input");
        return value;
    }

    private static final class Parser
    {
        private final String text;
        private int position;

        Parser(String text)
        {
            this.text = text;
        }

        boolean atEnd()
        {
            return position >= text.length();
        }

        double expression()
        {
            double value = term();
            while (!atEnd())
            {
                char op = text.charAt(position);
                if (op != '+' && op != '-')
                    break;
                position++;
                double right = term();
                value = op == '+' ? value + right : value - right;
            }
            return value;
        }

        private double term()
        {
            double value = factor();
            while (!atEnd())
            {
                char op = text.charAt(position);
                if (op != '*' && op != '/' && op != '%')
                    break;
                position++;
                double right = factor();
                if (op == '*')
                    value *= right;
                else if (op == '/')
                    value /= right;
                else
                    value %= right;
            }
            return value;
        }

        private double factor()
        {
            skipSpaces();
            if (atEnd())
                throw new IllegalArgumentException("missing operand");
            char c = text.charAt(position);
            if (c == '+')
            {
                position++;
                return factor();
            }
            if (c == '-')
            {
                position++;
                return -factor();
            }
            if (c == '(')
            {
                position++;
                double value = expression();
                skipSpaces();
                if (atEnd() || text.charAt(position) != ')')
                    throw new IllegalArgumentException("missing bracket");
                position++;
                return value;
            }
            int start = position;
            while (!atEnd())
            {
                char digit = text.charAt(position);
                if (!(Character.isDigit(digit) || digit == '.'))
                    break;
                position++;
            }
            if (start == position)
                throw new IllegalArgumentException("bad number");
            try
            {
                return Double.parseDouble(text.substring(start, position));
            }
            catch (NumberFormatException e)
            {
                throw new IllegalArgumentException("bad number");
            }
        }

        private void skipSpaces()
        {
            while (!atEnd() && Character.isWhitespace(text.charAt(position)))
                position++;
        }
    }
}
