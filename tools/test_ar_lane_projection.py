import unittest
import numpy as np
from ar_lane_projection import project_lanes, project_paths, project_leads


class ProjectionTest(unittest.TestCase):
    def setUp(self):
        x = np.linspace(0, 100, 33)
        self.lines = [np.column_stack((x, np.full_like(x,y), np.full_like(x,1.2))) for y in (-5.4,-1.8,1.8,5.4)]
        self.k = np.array([[900,0,964],[0,900,604],[0,0,1]])
        self.r = np.array([[0,1,0],[0,0,1],[1,0,0]])
        self.crop = (6.,264.,1915.,1191.)
    def run_projection(self, lines=None, probabilities=None, crop=None):
        return project_lanes(self.lines if lines is None else lines,
                             [1.]*4 if probabilities is None else probabilities,self.k,self.r,crop or self.crop)
    def test_left_current_right(self):
        self.assertEqual([-1,0,1],[x['relative'] for x in self.run_projection()])
    def test_crop_transform_matches_direct_projection(self):
        point=self.run_projection()[1]['points'][0]
        expected=[(964-900*1.8/5-6)/1909,(604+900*1.2/5-264)/927]
        np.testing.assert_allclose(point,expected,atol=1e-6)
    def test_low_confidence_does_not_invent_neighbor(self):
        self.assertEqual([0],[p['relative'] for p in self.run_projection(probabilities=[.1,1,1,.1])])
    def test_crossing_lines(self):
        lines=list(self.lines);lines[2]=lines[1].copy()
        self.assertNotIn(0,[p['relative'] for p in self.run_projection(lines=lines)])
    def test_invalid_points(self):
        lines=[line.copy() for line in self.lines];lines[1][1,2]=np.nan
        self.assertEqual([1],[p['relative'] for p in self.run_projection(lines=lines)])
    def test_missing_line(self):
        self.assertEqual([],self.run_projection(lines=self.lines[:3]))
    def test_edges_veto_outside_fill_without_replacing_lane_lines(self):
        edges=[self.lines[1],self.lines[2]]
        result=project_lanes(self.lines,[1]*4,self.k,self.r,self.crop,edges,[.1,.1])
        self.assertEqual([0],[v['relative'] for v in result])
        self.assertEqual([],project_lanes([],[],self.k,self.r,self.crop,edges,[.1,.1]))
    def test_untrusted_edges_do_not_veto_valid_lane(self):
        result=project_lanes(self.lines,[1]*4,self.k,self.r,self.crop,self.lines[1:3],[2.,float('nan')])
        self.assertEqual(3,len(result))
    def test_line_paths_are_open_and_can_show_below_fill_threshold(self):
        result=project_paths(self.lines,[.3,.8,1,0],self.k,self.r,self.crop)
        self.assertEqual([0,1,2],[p['relative'] for p in result])
        self.assertEqual(16,len(result[0]['points']))
        self.assertEqual(.3,result[0]['alpha'])
        self.assertNotEqual(result[0]['points'][0],result[0]['points'][-1])
    def test_invalid_path_confidence_is_not_drawn(self):
        self.assertEqual([],project_paths(self.lines,[float('nan'),-1,2,0],self.k,self.r,self.crop))
    def test_lead_matches_cp_sign_height_and_speed(self):
        lead={'present':True,'dRel':30.,'yRel':2.,'vRel':-5.,'radar':True}
        p=project_leads([lead],[0,20,40],[0,.1,.2],1.2,25,self.k,self.r,self.crop)[0]
        self.assertAlmostEqual(p['x'],(964-900*2/30-6)/1909)
        self.assertAlmostEqual(p['y'],(604+900*1.3/30-264)/927)
        self.assertEqual(72,p['speedKph'])
        self.assertEqual(-18,p['relativeSpeedKph'])
    def test_duplicate_and_lost_leads(self):
        lead={'present':True,'dRel':30.,'yRel':0.,'vRel':0.,'radar':True}
        def project(leads):return project_leads(leads,[0,100],[0,0],1.2,0,self.k,self.r,self.crop)
        self.assertEqual(1,len(project([lead,dict(lead,dRel=32)])))
        self.assertEqual(2,len(project([lead,dict(lead,dRel=40)])))
        self.assertEqual([],project([dict(lead,present=False)]))
        self.assertEqual([],project([dict(lead,yRel=1000)]))
        self.assertEqual([],project([dict(lead,vRel=float('nan'))]))
    def test_stopped_model_path_keeps_lead_using_cp_last_matching_index(self):
        lead={'present':True,'dRel':10.,'yRel':0.,'vRel':0.,'radar':True}
        # Low-speed model paths are not necessarily sorted; CP does not drop the lead.
        x=[0.,-.00002,.0003,.00009,5.,11.,8.,20.]
        z=[0.,0.,0.,0.,.1,.2,.4,.8]
        p=project_leads([lead],x,z,1.2,0.,self.k,self.r,self.crop)[0]
        self.assertAlmostEqual(p['y'],(604+900*1.6/10-264)/927)
        self.assertEqual(0.,p['speedKph'])
        # Repeated future samples at standstill must also remain visible.
        self.assertEqual(1,len(project_leads([lead],[0.,0.,0.],[0.,0.,0.],
                                           1.2,0.,self.k,self.r,self.crop)))


if __name__=='__main__':unittest.main()
