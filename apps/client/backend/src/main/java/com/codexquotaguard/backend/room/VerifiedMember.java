package com.codexquotaguard.backend.room;

import java.security.PublicKey;

public record VerifiedMember(MemberProfile profile, PublicKey publicKey) {
}
