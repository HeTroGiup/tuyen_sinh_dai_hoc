package com.project.tuyensinhdaihoc.service_layer.impl;

import com.project.tuyensinhdaihoc.data_access_layer.model.Combination;
import com.project.tuyensinhdaihoc.data_access_layer.model.Subject;
import com.project.tuyensinhdaihoc.data_access_layer.model.UniversityDetail;
import com.project.tuyensinhdaihoc.data_access_layer.repository.CombinationRepo;
import com.project.tuyensinhdaihoc.data_access_layer.repository.SubjectRepo;
import com.project.tuyensinhdaihoc.data_access_layer.repository.UniversityDetailRepo;
import com.project.tuyensinhdaihoc.helper_layer.utils.Algo;
import com.project.tuyensinhdaihoc.helper_layer.utils.Calculate;
import com.project.tuyensinhdaihoc.service_layer.UniversalPointService;
import com.project.tuyensinhdaihoc.service_layer.UniversityDetailService;
import com.project.tuyensinhdaihoc.web_layer.dto.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class UniversityDetailServiceImpl implements UniversityDetailService {

    private final UniversityDetailRepo universityDetailRepo;
    private final SubjectRepo subjectRepo;
    private final CombinationRepo combinationRepo;
    private final UniversalPointService universalPointService;

    @Autowired
    public UniversityDetailServiceImpl(UniversityDetailRepo universityDetailRepo, SubjectRepo subjectRepo, CombinationRepo combinationRepo, UniversalPointService universalPointService) {
        this.universityDetailRepo = universityDetailRepo;
        this.subjectRepo = subjectRepo;
        this.combinationRepo = combinationRepo;
        this.universalPointService = universalPointService;
    }


    @Override
    public List<String> findAllDistinctBranch() {
        return universityDetailRepo.findAllDistinctBranchName();
    }

    @Override
    public List<String> findAllSubject() {
        List<String> returnList = new ArrayList<>();
        List<Subject> temp = subjectRepo.findAll();
        for(Subject sub: temp) {
            returnList.add(sub.getName());
        }
        return returnList;
    }

    @Override
    public List<String> findAllUnivGeographic() {
        return universityDetailRepo.findAllDistinctUniversityGeographic();
    }

    @Override
    public List<UnivNameVO> findAllUnivNameVO() {
        List<UnivNameVO> univNameVOList = new ArrayList<>();
        List<Object> listTemp = universityDetailRepo.findDistinctByUnivCodeAndUnivName();
        int i = 0;
        for (Object cdata: listTemp) {
            i++;
            Object[] obj= (Object[]) cdata;
            String code = (String)obj[0];
            String name = (String)obj[1];;
            univNameVOList.add(new UnivNameVO(i, code, name, code + " - " + name));
        }
        return univNameVOList;
    }

    public Double computeAdmissionProb(Double totalScore, UniversityDetail university) {
        int rank = university.getUnivRank();
        List<UniversalPointVO> dest = universalPointService.findAllUniversalPointVO();
        List<Double> distCurrYear = dest.get(0).getValueList();
        List<Double> distLastYear = dest.get(4).getValueList();

        int rankTotal = totalScore.intValue();
        Double sumLastDist = 0.0;
        for (int i = 29; i >= rankTotal; i-- ) {
            sumLastDist += distLastYear.get(i);
        }

        // multiply with student amount ratio
        sumLastDist = sumLastDist * university.getAmountStudent() / university.getLastYearAmountStudent();

        Double sumCurrDist = 0.0;
        int i;
        for (i = 29; i >= rankTotal; i-- ) {
            sumCurrDist += distCurrYear.get(i);
            if (sumCurrDist > sumLastDist) break;
        }

        Double estimatedScore = i + Math.random() * 3;

        Double prob = Math.exp((totalScore - estimatedScore) / 10.0);
        return prob;
    }

    @Override
    public List<UniversityDetailVO> HTGQDBasedOn(UserInputVO userInputVO) {
        //1. Lam tron diem
        userInputVO = Calculate.roundScore(userInputVO);

        //2. Tinh diem cac to hop co the tao ra trong 5 mon nay
        List<CombinationVO> combinationVOList = new ArrayList<>();
        List<SubjectScoreVO> subjectScoreVOS = userInputVO.getSubjectScoreVOList();
        for(int i = 0; i < 3; i++) {
            for(int j = i+1; j < 4; j++) {
                for(int k = j + 1; k < 5; k++) {
                    List<Combination> cb = combinationRepo.findByIdSub1AndIdSub2AndIdSub3(subjectScoreVOS.get(i).getId(),
                            subjectScoreVOS.get(j).getId(), subjectScoreVOS.get(k).getId());
                    if (cb != null && !cb.isEmpty()) {
                        combinationVOList.add(new CombinationVO(cb.get(0), subjectScoreVOS.get(i), subjectScoreVOS.get(j),
                                subjectScoreVOS.get(k), userInputVO.getPriorityScore(), userInputVO.getRegionScore()));
                    }
                }
            }
        }

        //3. Filter data based on : univLevel, univRegion, totalScore
        List<UniversityDetail> universityDetailList = new ArrayList<>();
        for(CombinationVO comb: combinationVOList) {
            List<UniversityDetail> list = universityDetailRepo.
                    findAllByUnivLevelAndGeographicAndCombinationCodeAndTotalScoreLessThan(
                            userInputVO.getUnivLevel(), userInputVO.getUnivRegion(),
                            comb.getCode(), comb.getTotalScore());
            universityDetailList.addAll(list);
        }

        //4. Prepare input matrix

        int matrixSize = universityDetailList.size();
        int[] arrayId = new int[matrixSize];
        double[] amountStudent = new double[matrixSize];
        double[] score = new double[matrixSize];
        double[] rank = new double[matrixSize];
        double[] mainSubject = new double[matrixSize];

        for(int i = 0; i < matrixSize; i++) {
            UniversityDetail univ = universityDetailList.get(i);
            arrayId[i] = univ.getId();
            amountStudent[i] = (double) univ.getAmountStudent();
            score[i] = univ.getTotalScore();
            rank[i] = (double) univ.getUnivRank();

            Double maxScore = 0.0;
            for (CombinationVO comVO : combinationVOList) {
                if (univ.getCombinationCode().equals(comVO.getCode())) {
                    maxScore = comVO.getTotalScore();
                }
            }
            mainSubject[i] = computeAdmissionProb(maxScore, univ);
        }

        // 4.1 Get set of weights
        int[] weights = new int[userInputVO.getWeightVOList().size()];
        int j = 0;
        for(WeightVO wVO: userInputVO.getWeightVOList()) {
            weights[j] = wVO.getWeight();
            j++;
        }

        //5. Run algorithms
        ArrayList<Integer> outputResult = Algo.modelTOPSIS(arrayId, amountStudent, score, rank, mainSubject, weights);

        //6. Show result
        List<UniversityDetailVO> universityDetailVOList = new ArrayList<>();
        int i = 0;
        for(Integer ix: outputResult) {
            for(UniversityDetail univDetail: universityDetailList) {
                if(ix == univDetail.getId()) {
                    universityDetailVOList.add(new UniversityDetailVO(univDetail, (i+1)));
                    i++;
                    break;
                }
            }
        }

        return universityDetailVOList;
    }

    @Override
    public List<UniversityDetailVO> findAllUniversityDetailVO() {
        List<UniversityDetailVO> universityDetailVOList = new ArrayList<>();
        List<UniversityDetail> universityDetailList = universityDetailRepo.findAll();
        for(UniversityDetail univ: universityDetailList) {
            universityDetailVOList.add(new UniversityDetailVO(univ, univ.getId()));
        }
        return universityDetailVOList;
    }

    @Override
    public List<UniversityDetailVO> findAllUniversityDetailVOByUnivCode(String univCode) {
        List<UniversityDetailVO> universityDetailVOList = new ArrayList<>();
        List<UniversityDetail> universityDetailList = universityDetailRepo.findByUnivCode(univCode);
        for(UniversityDetail univ: universityDetailList) {
            universityDetailVOList.add(new UniversityDetailVO(univ, univ.getId()));
        }
        return universityDetailVOList;
    }
}
