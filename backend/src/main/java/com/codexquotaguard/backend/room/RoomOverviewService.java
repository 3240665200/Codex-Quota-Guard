package com.codexquotaguard.backend.room;

import com.codexquotaguard.backend.quota.QuotaSnapshot;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class RoomOverviewService {

    private final RoomDocumentStore store;

    public RoomOverviewService(RoomDocumentStore store) {
        this.store = store;
    }

    public RoomOverview readOverview() {
        if (!Files.isRegularFile(store.root().resolve("room.json"))) {
            return RoomOverview.unconfigured();
        }

        RoomManifest manifest = store.readManifest();
        MembersReadResult membersResult = store.readMembers(manifest.roomId());
        SnapshotsReadResult snapshotsResult = store.readSnapshots(manifest.roomId(), membersResult.members());
        Map<String, QuotaSnapshot> snapshotsByMember = new HashMap<>();
        snapshotsResult.snapshots().forEach(snapshot -> snapshotsByMember.put(snapshot.memberId(), snapshot));

        List<RoomMemberView> members = new ArrayList<>();
        membersResult.members().values().stream()
                .sorted(Comparator.comparing(member -> member.profile().memberId()))
                .forEach(member -> members.add(new RoomMemberView(
                        member.profile().memberId(),
                        member.profile().displayName(),
                        member.profile().managedLogin(),
                        member.profile().enforcementConsent(),
                        snapshotsByMember.get(member.profile().memberId())
                )));

        List<String> warnings = new ArrayList<>(membersResult.warnings());
        warnings.addAll(snapshotsResult.warnings());
        return new RoomOverview(true, manifest.roomId(), manifest.name(), members, warnings);
    }
}
