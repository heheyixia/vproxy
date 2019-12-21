package vproxy.dns;

import vproxy.dns.rdata.RData;
import vproxy.util.ByteArray;
import vproxy.util.Logger;

import java.util.LinkedList;
import java.util.List;

// rfc1035 impl
public class Formatter {
    private Formatter() {
    }

    public static ByteArray format(DNSPacket packet) {
        fillPacket(packet);
        ByteArray ret = formatHeader(packet);
        for (DNSQuestion q : packet.questions) {
            ret = ret.concat(formatQuestion(q));
        }
        for (DNSResource r : packet.answers) {
            ret = ret.concat(formatResource(r));
        }
        for (DNSResource r : packet.nameServers) {
            ret = ret.concat(formatResource(r));
        }
        for (DNSResource r : packet.additionalResources) {
            ret = ret.concat(formatResource(r));
        }
        return ret;
    }

    public static void fillPacket(DNSPacket packet) {
        packet.qdcount = packet.questions.size();
        packet.ancount = packet.answers.size();
        packet.nscount = packet.nameServers.size();
        packet.arcount = packet.additionalResources.size();
        for (DNSQuestion q : packet.questions) {
            fillQuestion(q);
        }
        for (DNSResource r : packet.answers) {
            fillResource(r);
        }
        for (DNSResource r : packet.nameServers) {
            fillResource(r);
        }
        for (DNSResource r : packet.additionalResources) {
            fillResource(r);
        }
    }

    @SuppressWarnings("unused")
    public static void fillQuestion(DNSQuestion q) {
        // do nothing
    }

    public static void fillResource(DNSResource r) {
        if (r.rdataBytes == null) {
            r.rdataBytes = r.rdata.toByteArray();
        }
        r.rdlen = r.rdataBytes.length();
    }

    public static ByteArray formatHeader(DNSPacket packet) {
        int len = 2 // id
            + 2 // qr+opcode+aa+tc=rd+ra+Z+rcode
            + 2 // qdcount
            + 2 // ancount
            + 2 // nscount
            + 2; // arcount
        ByteArray header = ByteArray.allocate(len);
        byte qr_opcode_aa_tc_rd = 0;
        {
            if (packet.isResponse) qr_opcode_aa_tc_rd |= 0b10000000;
            int opcode = packet.opcode.code;
            opcode = opcode << 3;
            qr_opcode_aa_tc_rd |= opcode;
            if (packet.aa) qr_opcode_aa_tc_rd |= 0b00000100;
            if (packet.tc) qr_opcode_aa_tc_rd |= 0b00000010;
            if (packet.rd) qr_opcode_aa_tc_rd |= 0b00000001;
        }
        byte ra_z_rcode = 0;
        if (packet.ra) ra_z_rcode |= 0b10000000;
        ra_z_rcode |= packet.rcode.code;
        header
            .int16(0, packet.id)
            .set(2, qr_opcode_aa_tc_rd)
            .set(3, ra_z_rcode)
            .int16(4, packet.qdcount)
            .int16(6, packet.ancount)
            .int16(8, packet.nscount)
            .int16(10, packet.arcount)
        ;
        return header;
    }

    public static ByteArray formatDomainName(String domain) {
        if (domain.isEmpty()) {
            return ByteArray.from((byte) 0);
        }
        // add missing trailing dot
        if (!domain.endsWith(".")) {
            domain += ".";
        }
        ByteArray ret = null;
        int start = 0;
        int end;
        while (start < domain.length()) {
            end = domain.indexOf(".", start + 1);
            assert end != -1;
            String sub = domain.substring(start, end);
            start = end + 1;
            byte[] bytes = sub.getBytes();
            ByteArray arr = ByteArray.from((byte) bytes.length);
            if (bytes.length > 0) {
                arr = arr.concat(ByteArray.from(bytes));
            }
            if (ret == null) {
                ret = arr;
            } else {
                ret = ret.concat(arr);
            }
        }
        assert ret != null;
        return ret.concat(ByteArray.from((byte) 0));
    }

    public static ByteArray formatString(String s) {
        byte[] bytes = s.getBytes();
        ByteArray len = ByteArray.from((byte) bytes.length);
        if (bytes.length == 0) {
            return len;
        } else {
            return len.concat(ByteArray.from(bytes));
        }
    }

    public static ByteArray formatQuestion(DNSQuestion q) {
        ByteArray qname = formatDomainName(q.qname);
        ByteArray qtype_qclass = ByteArray.allocate(4);
        qtype_qclass.int16(0, q.qtype.code);
        qtype_qclass.int16(2, q.qclass.code);
        return qname.concat(qtype_qclass);
    }

    public static ByteArray formatResource(DNSResource r) {
        if (r.rawBytes != null) {
            return r.rawBytes;
        }
        ByteArray name = formatDomainName(r.name);
        ByteArray type_class_ttl_rdlen = ByteArray.allocate(10);
        type_class_ttl_rdlen.int16(0, r.type.code);
        type_class_ttl_rdlen.int16(2, r.clazz.code);
        type_class_ttl_rdlen.int32(4, r.ttl);
        type_class_ttl_rdlen.int16(8, r.rdlen);
        ByteArray ret = name.concat(type_class_ttl_rdlen).concat(r.rdataBytes);
        r.rawBytes = ret;
        return ret;
    }

    public static List<DNSPacket> parsePackets(ByteArray input) throws InvalidDNSPacketException {
        List<DNSPacket> packets = new LinkedList<>();
        int totalOffset = 0;
        while (totalOffset < input.length()) {
            ByteArray data = input.sub(totalOffset, input.length() - totalOffset);
            try {
                DNSPacket packet = new DNSPacket();
                int offset = parseHeader(packet, data);
                for (int i = 0; i < packet.qdcount; ++i) {
                    DNSQuestion q = new DNSQuestion();
                    ByteArray sub = data.sub(offset, data.length() - offset);
                    offset += parseQuestion(q, sub, input);
                    packet.questions.add(q);
                }
                for (int i = 0; i < packet.ancount; ++i) {
                    DNSResource r = new DNSResource();
                    ByteArray sub = data.sub(offset, data.length() - offset);
                    offset += parseResource(r, sub, input);
                    packet.answers.add(r);
                }
                for (int i = 0; i < packet.nscount; ++i) {
                    DNSResource r = new DNSResource();
                    ByteArray sub = data.sub(offset, data.length() - offset);
                    offset += parseResource(r, sub, input);
                    packet.nameServers.add(r);
                }
                for (int i = 0; i < packet.arcount; ++i) {
                    DNSResource r = new DNSResource();
                    ByteArray sub = data.sub(offset, data.length() - offset);
                    offset += parseResource(r, sub, input);
                    packet.additionalResources.add(r);
                }
                packets.add(packet);
                totalOffset += offset;
                assert Logger.lowLevelDebug("parsed packet: " + packet);
            } catch (IndexOutOfBoundsException e) {
                throw new InvalidDNSPacketException("not a complete packet", e);
            }
        }
        assert totalOffset == input.length();
        return packets;
    }

    public static DNSPacket.Opcode parseOpcode(int opcode) throws InvalidDNSPacketException {
        if (opcode == DNSPacket.Opcode.QUERY.code) {
            return DNSPacket.Opcode.QUERY;
        } else if (opcode == DNSPacket.Opcode.IQUERY.code) {
            return DNSPacket.Opcode.IQUERY;
        } else if (opcode == DNSPacket.Opcode.STATUS.code) {
            return DNSPacket.Opcode.STATUS;
        } else {
            throw new InvalidDNSPacketException("unknown opcode: " + opcode);
        }
    }

    public static DNSPacket.RCode parseRCode(int rcode) throws InvalidDNSPacketException {
        if (rcode == DNSPacket.RCode.NoError.code) {
            return DNSPacket.RCode.NoError;
        } else if (rcode == DNSPacket.RCode.FormatError.code) {
            return DNSPacket.RCode.FormatError;
        } else if (rcode == DNSPacket.RCode.ServerFailure.code) {
            return DNSPacket.RCode.ServerFailure;
        } else if (rcode == DNSPacket.RCode.NameError.code) {
            return DNSPacket.RCode.NameError;
        } else if (rcode == DNSPacket.RCode.NotImplemented.code) {
            return DNSPacket.RCode.NotImplemented;
        } else if (rcode == DNSPacket.RCode.Refused.code) {
            return DNSPacket.RCode.Refused;
        } else {
            throw new InvalidDNSPacketException("unknown rcode: " + rcode);
        }
    }

    public static String parseDomainName(ByteArray data, ByteArray rawPacket, int[] offsetHolder) {
        StringBuilder sb = new StringBuilder();
        int len = 0;
        int i = 0;
        for (; ; ++i) {
            byte b = data.get(i);
            if (len == 0) {
                // need to read a length
                if (b == 0) {
                    break;
                } else if ((b & 0b11000000) == 0b11000000) {
                    // is pointer
                    int offset = (b & 0b00111111) << 8;
                    byte b2 = data.get(++i);
                    offset |= (b2 & 0xff);
                    String name = parseDomainName(rawPacket.sub(offset, rawPacket.length() - offset), rawPacket, offsetHolder);
                    sb.append(name);
                    break; // pointer must be the last piece of the domain name
                } else {
                    len = b & 0xff;
                }
            } else {
                sb.append((char) b);
                --len;
                if (len == 0) {
                    sb.append(".");
                }
            }
        }
        String name = sb.toString();
        offsetHolder[0] = i + 1;
        return name;
    }

    public static DNSType parseQuestionType(int qtype) throws InvalidDNSPacketException {
        if (qtype == DNSType.AXFR.code) {
            return DNSType.AXFR;
        } else if (qtype == DNSType.MAILB.code) {
            return DNSType.MAILB;
        } else if (qtype == DNSType.MAILA.code) {
            return DNSType.MAILA;
        } else if (qtype == DNSType.ANY.code) {
            return DNSType.ANY;
        } else {
            return parseType(qtype);
        }
    }

    public static DNSType parseType(int type) throws InvalidDNSPacketException {
        if (type == DNSType.A.code) {
            return DNSType.A;
        } else if (type == DNSType.NS.code) {
            return DNSType.NS;
        } else if (type == DNSType.MD.code) {
            return DNSType.MD;
        } else if (type == DNSType.MF.code) {
            return DNSType.MF;
        } else if (type == DNSType.CNAME.code) {
            return DNSType.CNAME;
        } else if (type == DNSType.SOA.code) {
            return DNSType.SOA;
        } else if (type == DNSType.MB.code) {
            return DNSType.MB;
        } else if (type == DNSType.MG.code) {
            return DNSType.MG;
        } else if (type == DNSType.MR.code) {
            return DNSType.MR;
        } else if (type == DNSType.NULL.code) {
            return DNSType.NULL;
        } else if (type == DNSType.WKS.code) {
            return DNSType.WKS;
        } else if (type == DNSType.PTR.code) {
            return DNSType.PTR;
        } else if (type == DNSType.HINFO.code) {
            return DNSType.HINFO;
        } else if (type == DNSType.MINFO.code) {
            return DNSType.MINFO;
        } else if (type == DNSType.MX.code) {
            return DNSType.MX;
        } else if (type == DNSType.TXT.code) {
            return DNSType.TXT;
        } else if (type == DNSType.AAAA.code) {
            return DNSType.AAAA;
        } else if (type == DNSType.OPT.code) {
            return DNSType.OPT;
        } else {
            throw new InvalidDNSPacketException("unknown type: " + type);
        }
    }

    public static int parseHeader(DNSPacket packet, ByteArray data) throws InvalidDNSPacketException {
        {
            packet.id = data.uint16(0);
        }
        {
            byte qr_opcode_aa_tc_rd = data.get(2);
            if ((qr_opcode_aa_tc_rd & 0b10000000) == 0b10000000) {
                packet.isResponse = true;
            }
            int _i = qr_opcode_aa_tc_rd & 0xff;
            _i = _i >> 3;
            _i = _i & 0x0f;
            packet.opcode = parseOpcode(_i);
            if ((qr_opcode_aa_tc_rd & 0b00000100) == 0b00000100) {
                packet.aa = true;
            }
            if ((qr_opcode_aa_tc_rd & 0b00000010) == 0b00000010) {
                packet.tc = true;
            }
            if ((qr_opcode_aa_tc_rd & 0b00000001) == 0b00000001) {
                packet.rd = true;
            }
        }
        {
            byte ra_z_rcode = data.get(3);
            if ((ra_z_rcode & 0b10000000) == 0b10000000) {
                packet.ra = true;
            }
            int _i = ra_z_rcode & 0x0f;
            packet.rcode = parseRCode(_i);
        }
        {
            packet.qdcount = data.uint16(4);
            packet.ancount = data.uint16(6);
            packet.nscount = data.uint16(8);
            packet.arcount = data.uint16(10);
        }
        return 12;
    }

    public static DNSClass parseQuestionClass(int qclass) throws InvalidDNSPacketException {
        if (qclass == DNSClass.ANY.code) {
            return DNSClass.ANY;
        } else {
            return parseClass(qclass);
        }
    }

    private static DNSClass parseClass(int clazz) throws InvalidDNSPacketException {
        if (clazz == DNSClass.IN.code) {
            return DNSClass.IN;
        } else if (clazz == DNSClass.CS.code) {
            return DNSClass.CS;
        } else if (clazz == DNSClass.CH.code) {
            return DNSClass.CH;
        } else if (clazz == DNSClass.HS.code) {
            return DNSClass.HS;
        } else {
            throw new InvalidDNSPacketException("unknown class: " + clazz);
        }
    }

    public static int parseQuestion(DNSQuestion q, ByteArray data, ByteArray rawPacket) throws InvalidDNSPacketException {
        int[] offsetHolder = {0};
        q.qname = parseDomainName(data, rawPacket, offsetHolder);
        int offset = offsetHolder[0];
        int qtype = data.uint16(offset);
        int qclass = data.uint16(offset + 2);
        q.qtype = parseQuestionType(qtype);
        q.qclass = parseQuestionClass(qclass);
        return offset + 4;
    }

    public static int parseResource(DNSResource r, ByteArray data, ByteArray rawPacket) throws InvalidDNSPacketException {
        int[] offsetHolder = {0};
        r.name = parseDomainName(data, rawPacket, offsetHolder);
        int offset = offsetHolder[0];
        int type = data.uint16(offset);
        int clazz = data.uint16(offset + 2);
        int ttl = data.int32(offset + 4);
        int rdlen = data.uint16(offset + 8);
        r.type = parseType(type);
        if (r.type == DNSType.OPT) {
            r.clazz = DNSClass.NOT_CLASS; // this field is not class
        } else {
            r.clazz = parseClass(clazz);
        }
        r.ttl = ttl;
        r.rdlen = rdlen;
        if (r.rdlen < 0) {
            throw new InvalidDNSPacketException("invalid rdlen: " + r.rdlen);
        }

        offset = offset + 10;
        ByteArray rdataBytes = data.sub(offset, r.rdlen);
        r.rdataBytes = rdataBytes;
        RData rData = RData.newRData(r.type);
        if (rData != null) {
            rData.fromByteArray(rdataBytes, rawPacket);
            r.rdata = rData;
        }
        int len = offset + r.rdlen;
        r.rawBytes = data.sub(0, len);
        return len;
    }
}
