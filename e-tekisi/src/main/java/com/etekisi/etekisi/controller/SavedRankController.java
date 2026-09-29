package com.etekisi.etekisi.controller;

import com.etekisi.etekisi.dto.api.ApiRecords.SaveRankRequest;
import com.etekisi.etekisi.model.Role;
import com.etekisi.etekisi.model.SavedRank;
import com.etekisi.etekisi.repository.SavedRankRepository;
import com.etekisi.etekisi.security.CurrentUser;
import com.etekisi.etekisi.web.ApiException;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/saved-ranks")
public class SavedRankController {

    private final SavedRankRepository savedRankRepository;
    private final CurrentUser currentUser;

    public SavedRankController(SavedRankRepository savedRankRepository, CurrentUser currentUser) {
        this.savedRankRepository = savedRankRepository;
        this.currentUser = currentUser;
    }

    @GetMapping("/{commuterId}")
    public List<SavedRank> getSavedRanks(@PathVariable Long commuterId) {
        currentUser.requireSelf(commuterId, Role.COMMUTER);
        return savedRankRepository.findByCommuterIdOrderByCreatedAtDesc(commuterId);
    }

    @PostMapping("/{commuterId}")
    public SavedRank saveRank(@PathVariable Long commuterId, @Valid @RequestBody SaveRankRequest req) {
        currentUser.requireSelf(commuterId, Role.COMMUTER);
        SavedRank rank = new SavedRank();
        rank.setCommuterId(commuterId);
        rank.setName(req.name().trim());
        rank.setAddress(req.address().trim());
        return savedRankRepository.save(rank);
    }

    @DeleteMapping("/{id}")
    public void deleteRank(@PathVariable Long id) {
        SavedRank rank = savedRankRepository.findById(id).orElseThrow(() -> ApiException.notFound("Saved rank"));
        currentUser.requireSelf(rank.getCommuterId(), Role.COMMUTER);
        savedRankRepository.delete(rank);
    }
}
