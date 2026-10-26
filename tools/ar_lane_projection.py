"""Read-only camera-space lane projection. No navigation or control decisions."""
import numpy as np


def project_leads(leads, path_x, path_z, height, ego_speed, intrinsic, calibration, crop):
    """CP lead placement: (dRel, -yRel, path.z[index<=dRel] + camera height).
    Metrics travel with their own projected target, never with another ACK snapshot.
    """
    x, z = np.asarray(path_x), np.asarray(path_z)
    if (len(x) == 0 or len(x) != len(z) or not np.isfinite(x).all() or
        not np.isfinite(z).all() or
        not np.isfinite(height) or height <= 0 or not np.isfinite(ego_speed)):
        return []
    left, top, right, bottom = crop
    if right <= left or bottom <= top:
        return []
    result = []
    first_distance = None
    for index, lead in enumerate(leads[:2]):
        if not lead['present']:
            continue
        distance, lateral, relative = lead['dRel'], lead['yRel'], lead['vRel']
        if not all(np.isfinite(v) for v in (distance, lateral, relative)) or distance <= 0:
            continue
        if index == 0:
            first_distance = distance
        elif first_distance is not None and abs(distance-first_distance) <= 3:
            continue  # Same duplicate suppression as CP ChevronMetrics.
        # Match ModelRenderer._get_path_length_idx, including stopped/low-speed
        # model paths whose near-zero x samples can repeat or move backwards.
        indices = np.flatnonzero(x <= distance)
        idx = int(indices[-1]) if indices.size else 0
        point = np.asarray(intrinsic) @ np.asarray(calibration) @ [distance, -lateral, z[idx]+height]
        if not np.isfinite(point).all() or point[2] <= .1:
            continue
        uv = (point[:2]/point[2]-[left, top])/[right-left, bottom-top]
        if not np.all((uv >= 0) & (uv <= 1)):
            continue  # Do not pin an offscreen car to the screen border.
        result.append({'index': index, 'x': float(uv[0]), 'y': float(uv[1]),
                       'distanceM': float(distance), 'speedKph': float(max(0., ego_speed+relative)*3.6),
                       'relativeSpeedKph': float(relative*3.6), 'radar': bool(lead['radar'])})
    return result


def valid_line(line):
    v = np.asarray(line, dtype=float)
    return v if (v.ndim == 2 and v.shape[1] == 3 and len(v) >= 3 and
                 np.isfinite(v).all() and np.all(np.diff(v[:, 0]) > 0)) else None


def project_paths(lines, qualities, intrinsic, calibration, crop):
    """Open polylines, independent of recommendations; quality is display opacity."""
    result = []
    left, top, right, bottom = crop
    if right <= left or bottom <= top:
        return result
    transform = np.asarray(intrinsic) @ np.asarray(calibration)
    for index, (line, quality) in enumerate(zip(lines, qualities)):
        v = valid_line(line)
        if v is None or not np.isfinite(quality) or not .05 <= quality <= 1:
            continue
        start, end = max(5., v[0, 0]), min(50., v[-1, 0])
        if end-start < 5:
            continue
        x = np.linspace(start, end, 16)
        points = np.column_stack((x, np.interp(x, v[:, 0], v[:, 1]),
                                  np.interp(x, v[:, 0], v[:, 2]))) @ transform.T
        if not np.isfinite(points).all() or np.any(points[:, 2] <= .1):
            continue
        uv = (points[:, :2]/points[:, 2:3]-[left, top])/[right-left, bottom-top]
        if np.any(np.abs(uv) > 4):
            continue
        result.append({'relative': index, 'alpha': float(quality), 'points': np.round(uv, 6).tolist()})
    return result


def project_lanes(lines, probabilities, intrinsic, calibration, crop, edges=(), edge_stds=()):
    """Return visible adjacent lane polygons, normalized to the exact JPEG crop.

    Matches C3's lane-line z convention (no path-height offset). Never synthesize
    a missing line or extrapolate a lane beyond the observed longitudinal extent.
    """
    if len(lines) != 4 or len(probabilities) != 4:
        return []
    left, top, right, bottom = crop
    if not (right > left and bottom > top):
        return []
    transform = np.asarray(intrinsic) @ np.asarray(calibration)
    result = []
    for index in range(3):
        if not all(np.isfinite(p) and .5 <= p <= 1 for p in probabilities[index:index+2]):
            continue
        a, b = (np.asarray(line, dtype=float) for line in lines[index:index+2])
        if any(v.ndim != 2 or v.shape[1] != 3 or len(v) < 3 or
               not np.isfinite(v).all() or np.any(np.diff(v[:, 0]) <= 0) for v in (a, b)):
            continue
        start, end = max(5., a[0, 0], b[0, 0]), min(50., a[-1, 0], b[-1, 0])
        if end - start < 5:
            continue
        x = np.linspace(start, end, 16)
        sides = [np.column_stack((x, np.interp(x, v[:, 0], v[:, 1]),
                                  np.interp(x, v[:, 0], v[:, 2]))) for v in (a, b)]
        widths = sides[1][:, 1] - sides[0][:, 1]
        if np.any(widths < 1.8) or np.any(widths > 5.5):
            continue  # crossings, forks and implausible topology must not become a lane fill
        outside = False
        if len(edges) == 2 and len(edge_stds) == 2:
            for edge_index, (edge, std) in enumerate(zip(edges, edge_stds)):
                edge = valid_line(edge)
                if edge is None or not np.isfinite(std) or not 0 <= std <= .5:
                    continue
                observed = (x >= edge[0, 0]) & (x <= edge[-1, 0])
                boundary = np.interp(x[observed], edge[:, 0], edge[:, 1])
                # A trusted road edge can veto a fill, never supply a missing lane line.
                outside |= bool(np.any(sides[0][observed, 1] < boundary) if edge_index == 0
                                else np.any(sides[1][observed, 1] > boundary))
        if outside:
            continue
        points = np.concatenate((sides[0], sides[1][::-1])) @ transform.T
        if not np.isfinite(points).all() or np.any(points[:, 2] <= .1):
            continue
        uv = points[:, :2] / points[:, 2:3]
        uv = (uv - [left, top]) / [right-left, bottom-top]
        if np.any(np.abs(uv) > 4):
            continue
        result.append({'relative': index-1, 'points': np.round(uv, 6).tolist()})
    return result
