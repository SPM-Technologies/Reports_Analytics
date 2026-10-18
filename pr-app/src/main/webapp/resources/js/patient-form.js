// Registration form helpers: age from DOB, BMI, show/hide insurance and permanent address, doctor suggestions.
(function () {
    function $(id) { return document.getElementById(id); }

    var dob = $('dob'), age = $('age');
    function calcAge() {
        if (!dob.value) { age.readOnly = false; return; }
        var b = new Date(dob.value), t = new Date();
        var a = t.getFullYear() - b.getFullYear();
        if (t.getMonth() < b.getMonth() || (t.getMonth() === b.getMonth() && t.getDate() < b.getDate())) a--;
        age.value = a < 0 ? '' : a;
        age.readOnly = true;
    }
    dob.addEventListener('change', calcAge);

    // height and weight can be hidden by the hospital's form settings
    var h = $('height'), w = $('weight'), bmi = $('bmi');
    function calcBmi() {
        if (!h || !w) { bmi.value = ''; return; }
        var hm = parseFloat(h.value) / 100, kg = parseFloat(w.value);
        bmi.value = hm > 0 && kg > 0 ? (kg / (hm * hm)).toFixed(1) : '';
    }
    if (h && w) {
        h.addEventListener('input', calcBmi);
        w.addEventListener('input', calcBmi);
    }

    function toggle(checkbox, box, showWhenChecked) {
        function apply() {
            var show = checkbox.checked === showWhenChecked;
            box.style.display = show ? '' : 'none';
        }
        checkbox.addEventListener('change', apply);
        apply();
    }
    toggle($('hasIns'), $('insBox'), true);
    toggle($('permSame'), $('permBox'), false);

    // doctor suggestions: only the doctors of the chosen department
    var list = $('doctorList'), dept = $('deptId');
    if (list && dept) {
        var all = Array.prototype.map.call(list.options, function (o) { return o.cloneNode(true); });
        function filterDoctors() {
            list.innerHTML = '';
            all.forEach(function (o) {
                if (!dept.value || o.getAttribute('data-dept') === dept.value) { list.appendChild(o.cloneNode(true)); }
            });
        }
        dept.addEventListener('change', filterDoctors);
        filterDoctors();
    }

    calcAge();
    calcBmi();
})();

// Prevent double registration from a double-click: disable the button once the form is submitted.
document.getElementById('regForm').addEventListener('submit', function () {
    var btn = this.querySelector('button[type=submit]');
    btn.disabled = true;
    btn.textContent = 'Saving...';
});
