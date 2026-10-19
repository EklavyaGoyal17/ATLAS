"""OpenAPI reference and operation checks. Uses PyYAML; does not claim full OAS conformance."""
from pathlib import Path
import yaml
spec = yaml.safe_load((Path(__file__).resolve().parent.parent / 'docs/openapi.yaml').read_text(encoding='utf-8'))
refs=[]
def walk(value):
    if isinstance(value,dict):
        for key,child in value.items():
            if key=='$ref': refs.append(child)
            walk(child)
    elif isinstance(value,list):
        for child in value:walk(child)
walk(spec)
for ref in refs:
    assert ref.startswith('#/'),ref
    target=spec
    for part in ref[2:].split('/'):target=target[part.replace('~1','/').replace('~0','~')]
operations=[]
for path,resource in spec['paths'].items():
    for method,operation in resource.items():
        if method not in {'get','post','put','delete','patch'}:continue
        operations.append(operation['operationId'])
        assert 'responses' in operation and operation['responses'],(path,method)
        params=operation.get('parameters',[])+resource.get('parameters',[])
        for segment in path.split('/'):
            if segment.startswith('{'):
                name=segment[1:-1]
                assert any((p.get('name')==name and p.get('in')=='path') or '$ref' in p for p in params),(path,name)
assert len(operations)==len(set(operations)),'Duplicate operation IDs'
print(f'PASS: {len(operations)} operations; {len(refs)} resolved internal references. Not full OpenAPI conformance validation.')
