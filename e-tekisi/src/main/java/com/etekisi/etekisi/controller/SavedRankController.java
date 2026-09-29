package com.etekisi.etekisi.controller;

import com.etekisi.etekisi.model.SavedRank;
import com.etekisi.etekisi.repository.SavedRankRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/saved-ranks")
public class SavedRankController {

    @Autowired
    private SavedRankRepository savedRankRepository;

    @GetMapping("/{commuterId}")
    public List<SavedRank> getSavedRanks(@PathVariable Long commuterId) {
        return savedRankRepository.findByCommuterIdOrderByCreatedAtDesc(commuterId);
    }

    @PostMapping("/{commuterId}")
    public SavedRank saveRank(@PathVariable Long commuterId, @RequestBody Map<String, String> payload) {
        String name = payload.get("name");
        String address = payload.get("address");
        if (name == null || name.trim().isEmpty() || address == null || address.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name and address are required");
        }

        SavedRank rank = new SavedRank();
        rank.setCommuterId(commuterId);
        rank.setName(name.trim());
        rank.setAddress(address.trim());
        return savedRankRepository.save(rank);
    }

    @DeleteMapping("/{id}")
    public void deleteRank(@PathVariable Long id) {
        savedRankRepository.deleteById(id);
    }
}
