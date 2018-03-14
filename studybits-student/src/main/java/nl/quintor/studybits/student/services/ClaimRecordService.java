package nl.quintor.studybits.student.services;

import lombok.AllArgsConstructor;
import nl.quintor.studybits.student.model.Claim;
import nl.quintor.studybits.student.model.ClaimRecord;
import nl.quintor.studybits.student.model.Student;
import nl.quintor.studybits.student.repositories.ClaimOfferRecordRepository;
import org.dozer.Mapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@AllArgsConstructor(onConstructor = @__(@Autowired))
public class ClaimRecordService {
    private ClaimOfferRecordRepository claimOfferRecordRepository;
    private StudentService studentService;
    private Mapper mapper;

    private ClaimRecord toModel(Object claimRecord) {
        return mapper.map(claimRecord, ClaimRecord.class);
    }

    public List<ClaimRecord> findAllClaims(Long studentId) {
        Student owner = studentService
                .findById(studentId)
                .orElseThrow(() -> new IllegalArgumentException("Student with id not found."));

        return claimOfferRecordRepository
                .findAllByOwner(owner)
                .stream()
                .map(this::toModel)
                .collect(Collectors.toList());
    }

    public Optional<ClaimRecord> findById(Long claimId) {
        return claimOfferRecordRepository.findById(claimId);
    }

    public ClaimRecord createAndSave(Long studentId, Claim claim) {
        Student student = studentService
                .findById(studentId)
                .orElseThrow(() -> new IllegalArgumentException("Student with id not found."));
        ClaimRecord claimRecord = new ClaimRecord(student, claim);

        return claimOfferRecordRepository.save(claimRecord);
    }

    public ClaimRecord updateClaimById(Long studentId, Long claimId, ClaimRecord claimRecord) {
        claimRecord.setId(claimId);
        return claimOfferRecordRepository.save(claimRecord);
    }
}
