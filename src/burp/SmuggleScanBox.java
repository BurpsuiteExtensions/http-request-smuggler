package burp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.zip.GZIPOutputStream;

public abstract class SmuggleScanBox extends Scan {

    ArrayList<String> supportedPermutations;
    static final String PERMUTE_PREFIX = "permute: ";

    SmuggleScanBox(String name) {
        super(name);
        Utilities.globalSettings.registerSetting("convert GET to POST", true);
        Utilities.globalSettings.registerSetting("globally swap - with _", false);
        //Utilities.globalSettings.registerSetting("report dodgy findings", false);
        supportedPermutations = new ArrayList<>();
        // core techniques
        registerPermutation("vanilla");
        registerPermutation("dualchunk");
        registerPermutation("badwrap");
        registerPermutation("space1");
        registerPermutation("badsetupLF");
        registerPermutation("gareth1");

        // niche techniques
        // registerPermutation("underjoin1");
        registerPermutation("spacejoin1");
        //registerPermutation("underscore2");
        registerPermutation("space2");
        registerPermutation("nameprefix1");
        registerPermutation("valueprefix1");
        registerPermutation("nospace1");
        registerPermutation("commaCow");
        registerPermutation("cowComma");
        registerPermutation("contentEnc");
        registerPermutation("linewrapped1");
        registerPermutation("quoted");
        registerPermutation("aposed");
        registerPermutation("badsetupCR");
        registerPermutation("vertwrap");
        registerPermutation("tabwrap");

        // new techniques for AppSec
        registerPermutation("lazygrep");
        registerPermutation("multiCase");
        registerPermutation("0dwrap");
        registerPermutation("0dspam");
        registerPermutation("revdualchunk");
        registerPermutation("nested");

        // new techniques for BHEU
        registerPermutation("chunky");
//        registerPermutation("bodysplit");
//        registerPermutation("0dsuffix");
//        registerPermutation("tabsuffix");
//        registerPermutation("UPPERCASE");
//        registerPermutation("reversevanilla");
//        registerPermutation("spaceFF");
//        registerPermutation("accentTE");
//        registerPermutation("accentCH");
//        registerPermutation("unispace");
//        registerPermutation("connection");

        for(int i: DesyncBox.getSpecialChars()) {
            registerPermutation("spacefix1:"+i);
        }
        for(int i: DesyncBox.getSpecialChars()) {
            registerPermutation("prefix1:"+i);
        }
        for(int i: DesyncBox.getSpecialChars()) {
            registerPermutation("suffix1:"+i);
        }
    }

    byte[] setupRequest(byte[] baseReq) {
        if (baseReq[0] == 'G') {
            if (Utilities.globalSettings.getBoolean("convert GET to POST")) {
                baseReq = Utilities.helpers.toggleRequestMethod(baseReq);
            }
            else {
                baseReq = Utilities.addOrReplaceHeader(baseReq, "Content-Type", "application/x-www-form-urlencoded");
                baseReq = Utilities.addOrReplaceHeader(baseReq, "Content-Length", "0");
            }
        }

        return baseReq;
    }

    @Override
    protected List<IScanIssue> doScan(byte[] baseReq, IHttpService service) {
        HashMap<String, Boolean> config;

        ArrayList<String> validPermutations = new ArrayList<>();
        for (String permutation: supportedPermutations) {
            String key = permutation+service.getProtocol()+service.getHost();
            if (BurpExtender.hostsToSkip.containsKey(key)) {
                if (Utilities.globalSettings.getBoolean("skip vulnerable hosts")) {
                    return null;
                }
                else if (Utilities.globalSettings.getBoolean("skip obsolete permutations")) {
                    validPermutations.add(permutation);
                }
            }
        }

        if (validPermutations.isEmpty()) {
            validPermutations.addAll(supportedPermutations);
        }

        for (String permutation: validPermutations) {
            if (!Utilities.globalSettings.getBoolean(PERMUTE_PREFIX+permutation)) {
                continue;
            }

            config = new HashMap<>();
            config.put(permutation, true);
            boolean worked = doConfiguredScan(baseReq, service, config);
            if (worked) {
                String key = permutation+service.getProtocol()+service.getHost();
                BurpExtender.hostsToSkip.putIfAbsent(key, true);
                if (Utilities.globalSettings.getBoolean("skip obsolete permutations")) {
                    break;
                }
            }
        }
        return null;
    }

    abstract boolean doConfiguredScan(byte[] baseReq, IHttpService service, HashMap<String, Boolean> config);

    void registerPermutation(String permutation) {
        supportedPermutations.add(permutation);
        Utilities.globalSettings.registerSetting(PERMUTE_PREFIX+permutation, true);
    }


    Resp leftAlive(byte[] req, IHttpService service) {
        byte[] keepalive = Utilities.setHeader(req, "Connection", "keep-alive");
        Resp resp = request(service, keepalive);
        String connectionType = Utilities.getHeader(resp.getReq().getResponse(), "Connection");
        if (connectionType.toLowerCase().contains("alive")) {
            return resp;
        }
        return null;
    }

    static Resp buildPoc(byte[] req, IHttpService service, HashMap<String, Boolean> config) {
        try {
            byte[] badMethodIfChunked = Utilities.setHeader(req, "Connection", "keep-alive");
            badMethodIfChunked = makeChunked(badMethodIfChunked, 1, 0, config, false);

            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            buf.write(badMethodIfChunked);
            buf.write("G".getBytes());

            // first request ends here
            buf.write(makeChunked(req, 0, 0, config, false));
            return new Resp(new Req(buf.toByteArray(), null, service), System.currentTimeMillis());
        }
        catch (IOException e) {
            throw new RuntimeException();
        }
    }

    static byte[] gzipBody(byte[] baseReq) {
        try {
            byte[] req = Utilities.addOrReplaceHeader(baseReq, "Transfer-Encoding", "gzip");
            String body = Utilities.getBody(req);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            GZIPOutputStream gzip = new GZIPOutputStream(out);
            gzip.write(Utilities.helpers.stringToBytes(body));
            gzip.close();
            return Utilities.setBody(req, Utilities.helpers.bytesToString(out.toByteArray()));
        } catch (Exception e) {
            Utilities.err(e.getMessage());
            return baseReq;
        }
    }

    static byte[] makeChunked(byte[] baseReq, int contentLengthOffset, int chunkOffset) {
        return makeChunked(baseReq, contentLengthOffset, chunkOffset, new HashMap<>(), false);
    }

    static byte[] makeChunked(byte[] baseReq, int contentLengthOffset, int chunkOffset, HashMap<String, Boolean> settings, boolean malformedClose) {
        if (!Utilities.containsBytes(baseReq, "Transfer-Encoding".getBytes())) {
            baseReq = Utilities.addOrReplaceHeader(baseReq, "Transfer-Encoding", "foo");
        }

        byte[] chunkedReq = DesyncBox.applyDesync(baseReq, settings);

        if (Utilities.globalSettings.getBoolean("globally swap - with _")) {
            chunkedReq = Utilities.replace(chunkedReq, "Transfer-Encoding".getBytes(), "Transfer_Encoding".getBytes());
        }

        String ending = "0\r\n\r\n";
        if (malformedClose) {
            if (Utilities.globalSettings.getBoolean("risky mode")) {
                ending = "1\r\nZ\r\n0\r\n\r\n";
            }
            else {
                ending = "1\r\nZ\r\nQ\r\n\r\n";
            }
        }

        int bodySize = baseReq.length - Utilities.getBodyStart(baseReq);
        String body = Utilities.getBody(baseReq);

        // concept by @webtonull
        if (Utilities.globalSettings.getBoolean("pad everything") || settings.containsKey("chunky")) {
            String padChunk = "F\r\nAAAAAAAAAAAAAAA\r\n";
            StringBuilder fullPad = new StringBuilder();
            for (int i=0; i<3000; i++) {
                fullPad.append(padChunk);
            }
            ending = fullPad.toString() + ending;
        }

        int chunkSize = bodySize+chunkOffset;
        if (chunkSize > 0) {
            chunkedReq = Utilities.setBody(chunkedReq, Integer.toHexString(chunkSize) + "\r\n" + body + "\r\n"+ending);
        }
        else {
            chunkedReq = Utilities.setBody(chunkedReq, ending);
        }
        bodySize = chunkedReq.length - Utilities.getBodyStart(chunkedReq);
        String newContentLength = Integer.toString(bodySize+contentLengthOffset);

        chunkedReq = Utilities.setHeader(chunkedReq, "Content-Length", newContentLength);
        if (settings.containsKey("reversevanilla")) {
            chunkedReq = Utilities.replace(chunkedReq, "Content-Length", "oldContentLength");
            chunkedReq = Utilities.addOrReplaceHeader(chunkedReq, "Content-Length", newContentLength);
        }

        // fixme breaks stuff
//        if (settings.containsKey("underscore2")) {
//            chunkedReq = Utilities.replace(chunkedReq, "Content-Length".getBytes(), "Content_Length".getBytes());
//        }
//        else if (settings.containsKey("space2")) {
//            chunkedReq = Utilities.replace(chunkedReq, "Content-Length".getBytes(), "Content-Length ".getBytes());
//        }

        return chunkedReq;
    }


        boolean sendPoc(String name, byte[] setupAttack, byte[] victim, IHttpService service) {
        return sendPoc(name, Utilities.helpers.bytesToString(setupAttack), victim, service, new HashMap<>());
    }

    boolean sendPoc(String name, byte[] setupAttack, byte[] victim, IHttpService service, HashMap<String, Boolean> config) {
        return sendPoc(name, Utilities.helpers.bytesToString(setupAttack), victim, service, config);
    }


    boolean sendPoc(String name, String setupAttack, byte[] victim, IHttpService service, HashMap<String, Boolean> config) {
        try {
            Resp baseline = request(service, victim);
            SmuggleHelper helper = new SmuggleHelper(service);
            helper.queue(setupAttack);
            helper.queue(Utilities.helpers.bytesToString(victim));
            helper.queue(Utilities.helpers.bytesToString(victim));

            List<Resp> results = helper.waitFor();
            Resp cleanup = null;
            for (int i=0;i<3;i++) {
                cleanup = request(service, victim);
                if (cleanup.getInfo().getStatusCode() != baseline.getInfo().getStatusCode()) {
                    request(service, victim);
                    break;
                }
            }
            int cleanupStatus = cleanup.getStatus();
            int minerStatus = results.get(0).getStatus();
            int victimStatus = results.get(1).getStatus();

            if (cleanupStatus == minerStatus && minerStatus == victimStatus) {
                return false;
            }

            HashSet<Integer> badCodes = new HashSet<>();
            badCodes.add(0);
            badCodes.add(428);
            badCodes.add(429);
            badCodes.add(430);

            if (badCodes.contains(cleanupStatus) || badCodes.contains(minerStatus) || badCodes.contains(victimStatus)) {
                return false;
            }

            String issueTitle;
            String issueDescription = "";

            if (minerStatus == victimStatus || cleanupStatus == minerStatus) {
                issueTitle = "HTTP Request Smuggling Confirmed";
            }
            else if (cleanupStatus == victimStatus) {
                return false;
            }
            else {
                issueTitle = "HTTP Request Smuggling maybe";
            }

            helper = new SmuggleHelper(service);
            int randomCheckCount = 7;
            if (Utilities.globalSettings.getBoolean("skip straight to poc")) {
                randomCheckCount = 14;
            }

            for (int i=0; i<randomCheckCount;i++) {
                helper.queue(Utilities.helpers.bytesToString(victim));
            }
            List<Resp> cleanResults = helper.waitFor();
            for (int i=0; i<randomCheckCount;i++) {
                if (cleanResults.get(i).getStatus() != baseline.getInfo().getStatusCode()) {
                    issueTitle += " (dodgy)";
                    break;
                }
            }

            issueTitle += ": "+name + " -";

            issueTitle += String.join("|", config.keySet());

            report(issueTitle, issueDescription, cleanup, results.get(0), results.get(1));

            BurpExtender.hostsToSkip.putIfAbsent(service.getHost(), true);
            return true;
        }
        catch (Exception e) {
            return false;
        }
    }

}
